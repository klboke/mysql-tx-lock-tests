package www.kailing.pub;

import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;

/**
 * 串行化事务隔离级别，解决幻读问题
 * 验证步骤：在事务A适当位置加上断点，然后启动事务A，启动事务B
 *
 * @author: kl @kailing.pub
 * @date: 2019/4/4
 */
public class TransactionSerializableTest extends BaseTest {

    /**
     * 事务A：
     * 事务A查询后更新前判断的间隙加上断点，
     */
    @Test
    public void testTxA() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");
            if (list.size() == 0) {//此处需要断点模拟操作间隙。
                jdbcTemplate.execute("INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)");
            }
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

    /**
     * 事务B：
     * 在事务A已经打开的情况下，事务B后开启,这个时候因为事务A加上了Serializable的隔离级别，查询语句已经给id=31的记录加上了共享锁，
     * 所以事务B在查询成功后执行更新的时候事务就会进入锁等待状态，如果事务A一直不提交，事务B最终会应用锁等待超时结束
     *
     */
    @Test
    public void testTxB() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");
            System.out.println("获取共享锁成功，查询执行完成");
            if (0 == list.size()) {
                jdbcTemplate.execute("INSERT INTO USER (id,name,card,age) VALUES (31,'kl','00',66)");
                System.out.println("获取排它锁成功，执行更新完成");
            }
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

}
