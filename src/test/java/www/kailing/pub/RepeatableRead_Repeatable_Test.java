package www.kailing.pub;

import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;

/**
 * 测试ISOLATION_REPEATABLE_READ 解决重复读问题
 * 测试步骤：先在testTxA()适当位置加上断点，然后启动事务A，接着启动事务B，然后释放事务A的断点
 *
 * @author: kl @kailing.pub
 * @date: 2019/4/4
 */
public class RepeatableRead_Repeatable_Test extends BaseTest {
    /**
     * 事务A：
     * 事务A先去查找记录ID=30的记录，然后输出，此时记录数为0，然后等待事务B新增数据后再次查询，你会发现，数据库虽然已经有这条记录了
     * 但是事务A第二次查询也是0
     */
    @Test
    public void testTxA() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 30");
            System.out.println("第一次查询结果：" + list.size());
            List list1 = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");//此处断点，等待事务A提交新纪录
            System.out.println("第二次查询结果：" + list1.size());
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

    @Test
    public void testTxC() {
//        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
//        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
//        def.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
//        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            jdbcTemplate.execute("UPDATE user SET card = '00' WHERE  id =31");
            //dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            //dataSourceTransactionManager.rollback(status);
        }
    }

    /**
     * 事务B：在事务A已经打开的情况下，事务B插入一天记录
     */
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

}

