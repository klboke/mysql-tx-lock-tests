# 前言

这篇博文源于公司一个批处理的项目异常而起的。先简单描述下发生背景。一个基于spring batch开发的批处理应用，线上运行了9个多月后，某一天突然跑批任务失败了，检查日志得知，是因为一个mysql异常导致的：Lock wait timeout exceeded。msyql事务锁等待超时这个异常虽然不常见，但随便一搜就会看到大量的相关的信息。导致这个异常的原因就是mysql数据库事务锁等待超时，默认超时时间是50S。但我们的批处理业务从逻辑上讲不会出现这种事务排他锁等待的情况，不得其解。故通过以下这些实例来捋一捋mysql事务内隔离级别和锁等知识点，看看是否如我们了解的这样，同时加深下印象。

# 讲什么？

通读本文你能了解到，mysql InnoDB事务是通过锁+MVCC（多版本并发控制）来解决并发问题的，然后什么情况下会发生Lock wait timeout exceeded，也就是什么情况下会加锁，加什么锁，以及怎么排查数据库死锁等问题。主要是提供一些思路。

说明：此文适用mysql版本5.7及以下

# 事务的隔离级别

先了解下mysql的事务隔离级别，这个也是老生常谈的一个知识点，也是面试比较常问的一个问题，不过能够以自己的理解描述出来的人不多

读未提交（read-uncommitted）：存在【脏读】、【不可重复度】、【幻读】的问题

读已提交（read-committed） ：存在【不可重复度】、【幻读】的问题

可重复读（repeatable-read）：存在【幻读】的问题

串行化（serializable）：没问题。

-   【脏读】：事务A读取到了事务B未提交的内容，事务B在后面可能事务回滚了，那么事务A读取的记录就是脏数据了。
-   【不可重复读】：事务A在一个事务内读取同一个记录两次，由于事务B在事务A读取的间隙修改了数据，导致事务A两次读取到的记录不一样。
-   【幻读】：事务A查询一条记录是否存在而去插入这条记录，查询出来不存在，当执行插入前间隙的时候，事务B插入了这条记录，这个时候事务A插入就会失败，就像幻觉一样，明明刚刚查询的时候这条记录还不存在

关于幻读，网上有大量的针对【幻读】的解读，其实都是有误解的。幻读其实是类似这种根据查询某些符合条件的记录去做相关的业务的，比如事务A查询记录1的值，复制这条记录，然后保存，这个时候事务结束后期望的是复制的这条记录和原始记录是一样的。然而如果发生幻读现象，事务B在事务A复制这条新纪录的间隙修改了原记录的值，那么新的这个复制记录的值和原记录的值就不相等了。网上很多说幻读是事务两次读取的记录的行数不一致的问题（也就是幻读针对的是ROW的新增，针对插入动作）其实是不对的，这种情况下RR的隔离级别通过间隙锁（Gap Locks）和MVCC就可以避免了。简而言之就是A事务内查询的数据可能在A事务未完成之前被事务B修改了，A事务再次读取记录验证时与预期不符。

# InnoDB的事务、锁、等表

mysql有一个系统数据库，里面有很多表，其中，如下三张表保存了事务相关的信息，如当前发生的事务，以及当前锁定的记录，和当前事务等待锁的信息等。下面的实例最终需要结合这些表的记录信息来分析验证，所以我们先看看这些表具体记录了哪些信息，能给我们平时排查问题带来哪些帮助。

INNODB_TRX：InnoDB的事务表,每次开启事务这里都会有记录，可以方便的查看当前正在执行以及正在等待执行的事务信息。包括执行的sql、事务的隔离级别、相关联表的数量等信息，详情如下：

```sql
CREATE TEMPORARY TABLE `INNODB_TRX` (
  `trx_id` varchar(18) NOT NULL DEFAULT '', #内部的唯一交易ID号
  `trx_state` varchar(13) NOT NULL DEFAULT '', #事务执行状态。允许值是 RUNNING，LOCK WAIT， ROLLING BACK，和 COMMITTING
  `trx_started` datetime NOT NULL DEFAULT '0000-00-00 00:00:00', #交易开始时间。
  `trx_requested_lock_id` varchar(81) DEFAULT NULL, #事务当前正在等待的锁的ID，如果TRX_STATE不是LOCK WAIT; 则为NULL。
  `trx_wait_started` datetime DEFAULT NULL, #事务开始等待锁定的时间，如果 TRX_STATE不是LOCK WAIT; 则为NULL。
  `trx_weight` bigint(21) unsigned NOT NULL DEFAULT '0', #事务的权重，反映（但不一定是确切的计数）更改的行数和事务锁定的行数。
  `trx_mysql_thread_id` bigint(21) unsigned NOT NULL DEFAULT '0', #MySQL线程ID。
  `trx_query` varchar(1024) DEFAULT NULL, #事务正在执行的SQL语句。
  `trx_operation_state` varchar(64) DEFAULT NULL, #事务的当前操作，如果有的话; 否则 NULL。
  `trx_tables_in_use` bigint(21) unsigned NOT NULL DEFAULT '0', #InnoDB处理此事务的当前SQL语句时使用 的表数。
  `trx_tables_locked` bigint(21) unsigned NOT NULL DEFAULT '0', #InnoDB当前SQL语句具有行锁定 的表的数量
  `trx_lock_structs` bigint(21) unsigned NOT NULL DEFAULT '0', #事务保留的锁数。
  `trx_lock_memory_bytes` bigint(21) unsigned NOT NULL DEFAULT '0', #内存中此事务的锁结构占用的总大小。
  `trx_rows_locked` bigint(21) unsigned NOT NULL DEFAULT '0', #此交易锁定的大致数字或行数。该值可能包括实际存在但对事务不可见的删除标记行。
  `trx_rows_modified` bigint(21) unsigned NOT NULL DEFAULT '0', #此事务中已修改和插入的行数。
  `trx_concurrency_tickets` bigint(21) unsigned NOT NULL DEFAULT '0', #指示当前事务在被换出之前可以执行多少工作，由innodb_concurrency_tickets 系统变量指定 。
  `trx_isolation_level` varchar(16) NOT NULL DEFAULT '', #当前事务的隔离级别。
  `trx_unique_checks` int(1) NOT NULL DEFAULT '0', #是否为当前事务打开或关闭唯一检查。例如，在批量数据加载期间可能会关闭它们。
  `trx_foreign_key_checks` int(1) NOT NULL DEFAULT '0', #是否为当前事务打开或关闭外键检查。例如，在批量数据加载期间可能会关闭它们。
  `trx_last_foreign_key_error` varchar(256) DEFAULT NULL, #最后一个外键错误的详细错误消息（如果有）; 否则为NULL。
  `trx_adaptive_hash_latched` int(1) NOT NULL DEFAULT '0', #自适应哈希索引是否被当前事务锁定。当自适应哈希索引搜索系统被分区时，单个事务不会锁定整个自适应哈希索引。自适应哈希索引分区由 innodb_adaptive_hash_index_parts，默认设置为8。
  `trx_adaptive_hash_timeout` bigint(21) unsigned NOT NULL DEFAULT '0', #是否立即为自适应哈希索引放弃搜索锁存器，或者在MySQL的调用之间保留它。当没有自适应哈希索引争用时，该值保持为零，语句保留锁存器直到它们完成。在争用期间，它倒计时到零，并且语句在每次行查找后立即释放锁存器。当自适应散列索引搜索系统被分区（受控制 innodb_adaptive_hash_index_parts）时，该值保持为0。
  `trx_is_read_only` int(1) NOT NULL DEFAULT '0', #值为1表示事务是只读的。
  `trx_autocommit_non_locking` int(1) NOT NULL DEFAULT '0' #值为1表示事务是 SELECT不使用FOR UPDATEor LOCK IN SHARED MODE子句的语句，并且正在执行， autocommit因此事务将仅包含此一个语句。当此列和TRX_IS_READ_ONLY都为1时，InnoDB优化事务以减少与更改表数据的事务关联的开销。
) ENGINE=MEMORY DEFAULT CHARSET=utf8;
```

INNODB_LOCKS：InnoDB的锁信息，可以查询到当前事务使用了锁来控制并发的锁信息

```sql
CREATE TEMPORARY TABLE `INNODB_LOCKS` (
  `lock_id` varchar(81) NOT NULL DEFAULT '', #内部的唯一锁ID号。
  `lock_trx_id` varchar(18) NOT NULL DEFAULT '', #持有锁的事务的ID。
  `lock_mode` varchar(32) NOT NULL DEFAULT '', #锁定模式。允许锁定模式描述符 S，X， IS，IX， GAP，AUTO_INC。
  `lock_type` varchar(32) NOT NULL DEFAULT '', #锁的类型。行锁还是表锁。
  `lock_table` varchar(1024) NOT NULL DEFAULT '', #已锁定或包含锁定记录的表的名称。
  `lock_index` varchar(1024) DEFAULT NULL, #索引的名称，如果LOCK_TYPE是 RECORD; 否则NULL。
  `lock_space` bigint(21) unsigned DEFAULT NULL, #锁定记录的表空间ID，如果 LOCK_TYPE是RECORD; 否则NULL。
  `lock_page` bigint(21) unsigned DEFAULT NULL, #锁定记录的页码，如果 LOCK_TYPE是RECORD; 否则NULL。
  `lock_rec` bigint(21) unsigned DEFAULT NULL, #页面内锁定记录的堆号，如果 LOCK_TYPE是RECORD; 否则NULL。
  `lock_data` varchar(8192) DEFAULT NULL #与锁相关的数据（如：主键ID值，索引列的值）。如果LOCK_TYPE是RECORD，则显示值，否则显示值NULL。
) ENGINE=MEMORY DEFAULT CHARSET=utf8;
```

INNODB\_LOCK\_WAITS：Innodb锁等待信息，主要记录了当前执行事务ID和锁ID以及等待执行的事务ID和锁ID的关系。

```sql
CREATE TEMPORARY TABLE `INNODB_LOCK_WAITS` (
  `requesting_trx_id` varchar(18) NOT NULL DEFAULT '', #请求事务（被阻止）的ID。
  `requested_lock_id` varchar(81) NOT NULL DEFAULT '', #事务正在等待的锁的ID。
  `blocking_trx_id` varchar(18) NOT NULL DEFAULT '', #当前运行事务的ID。
  `blocking_lock_id` varchar(81) NOT NULL DEFAULT '' #进行的事务所持有的锁的ID。
) ENGINE=MEMORY DEFAULT CHARSET=utf8;
```

基于这些表的信息，可以帮助我们诊断在并发负载较重时发生的性能问题。

# mysql的锁信息

以下是mysql事务中比较常见的锁的信息，

X（排它锁）：也叫独占锁，亦称写锁。事务A持有X后，事务B无论请求X锁还是S锁都只能等待A事务的释放。

S（共享锁）：也叫读锁。事务A持有S锁后，事务B请求S锁会立即授予，如果请求X锁照样需要等待。例如，串行化事务隔离级别对读取的记录加S读锁了，当其他的事务需要读取这条记录的时候会立即授予，当其他事务需要修改这条记录获取X写锁时就必须阻塞等待了

GAP（间隙锁）：锁定一个区间的记录数。例如，`SELECT c1 FROM t WHERE c1 BETWEEN 10 and 20 FOR UPDATE;`阻止其他事务将值`15`插入列`t.c1`，无论列 中是否已存在任何此类值，因为该范围中所有现有值之间的间隙都已锁定。

mysql中关于锁的内容不止上面这些内容，单独拿mysql的锁也可以写一篇长文，所以这里只列出了常用的锁模式，足够解决我们平时遇到的各种事务问题，更多关于msyql锁的信息，可以移步文档：[https://dev.mysql.com/doc/refman/5.7/en/innodb-locking.html](https://dev.mysql.com/doc/refman/5.7/en/innodb-locking.html)

# 快照读和当前读

思考个问题。在RR级别下的事务在没有使用锁的情况下，是怎么解决一致性读的问题的？是因为mysql使用了MVCC（多版本并发控制）技术，事务内首次读时生成了快照，再次读记录其实就是读取的快照的内容，所以就涉及到了快照读和当前读：

快照读：读取数据的可见版本。不加事务的select操作都是快照读，加事务的情况下要看事务隔离级别。如READ_UNCOMMITTED情况下就可能读到最新未提交的记录

当前读：读取数据的最新版本。如带lock in share mode、 for update的select，以及增删改等操作都是当前读。需要对当前的主键以及唯一索引数据加锁

# 测试实例

先创建一张表user表，包含如下的字段，如：

```sql
CREATE TABLE `user` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `card` varchar(255) DEFAULT NULL,
  `age` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `card_index` (`card`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=latin1;
```

id为主键，并给card字段设置唯一索引

## repeatable-read隔离级别

这个是mysql默认的隔离级别，故在使用spring tx时，如若不指定事务的隔离级别默认就是repeatable-read。这个隔离级别下有效的防止了脏读和不可重复读，但是不能防止幻读。下面我们来看下这种事务隔离级别下事务锁的情况。

如下测试用例：

```java
    @Test
    public void testTxB() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            jdbcTemplate.execute("INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)");
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");//此处断点，等待事务A提交新纪录
            System.out.println("查询结果：" + list.size());
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }
```

在事务提交前打上断点，分别两次执行测试用例。你会发现，第一次会进断点，第二次执行时直接在execute处就阻塞死了。这个时候事务记录表当前有两个事务，然后有个事务状态是LOCK WAIT的状态。下面是详细记录：

INNODB_TRX：

![](https://oscimg.oschina.net/oscnet/5b17ac0394099604fa4f74e9a584b3f8349.jpg)

可以看到事务id：4473463正在执行中，事务id：4473464正在请求锁，包括执行的sql语句和事务的线程id等信息

INNODB_LOCKS：

![](https://oscimg.oschina.net/oscnet/6d93562e1913ed95a22a18045be1dd0a63a.jpg)

锁的信息记录了当前锁定了id为31的记录。事务4473463持有了写锁。锁的索引类型为PRIMARY等信息，所以从这里可以看出mysql首先是根据主键记录来加锁的，当主键不不冲突时，在看唯一索引是否需要加锁。

INNODB\_LOCK\_WAITS：

![](https://oscimg.oschina.net/oscnet/0a765e528128c62e21f2de36a75fdd3a84f.jpg)

上面测试的主要是分别写入相同主键记录的情况，还有其他的一些情况，这里就不贴代码和表记录的图片了，稍微做下总结：

repeatable-read级别：

执行：INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)，阻塞事务提交后

针对主键31、和唯一索引列‘00’相关的insert、update、delete都会加锁

读这条记录的操作（where 条件为id或唯一索引记录）不加锁

transaction-serializable级别：

执行：INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)，阻塞事务提交后

针对主键31、和唯一索列‘00’相关的insert、update、delete都会加锁

读这条记录的操作（where 条件为id或唯一索引记录）也会加锁

如上，所以加锁互斥的操作，如果由等待锁的时间太长都会抛Lock wait timeout exceeded的异常

# 结语

mysql数据库是每个做后端开发的不可避免的要去了解的东西，对于mysql事务和数据锁这块博主之前也一直停留在教程和博文上面，这次因为生产的异常，又没有专职的DBA，所以去系统的验证了下事务锁相关的东西，包括去看了mysql的官方文档。收获还是比较大的，对后面分析类似问题有很大的帮助。由于个人能力有限，可能很多地方理解和网上的不一样，主要是带着一种解决自己疑惑的心态去理解的，可能和实际有偏差，欢迎指正。建议有兴趣的可以去看看mysql的官方文档：

[https://dev.mysql.com/doc/refman/5.7/en/innodb-introduction.html](https://dev.mysql.com/doc/refman/5.7/en/innodb-introduction.html)

在写博文的过程中和同事交流时给我推荐了一个博客，感觉非常不错，其实之前也有看过这个博文，不过没自己手动验证的情况下，印象不深刻，在这里也推荐大家去看看。是阿里数据库团队技术专家[何登成](http://hedengcheng.com/)的博客：

[http://hedengcheng.com/?p=771](http://hedengcheng.com/?p=771)
我的博客：http://www.kailing.pub
