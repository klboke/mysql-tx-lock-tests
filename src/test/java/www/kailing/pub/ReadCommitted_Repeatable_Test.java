package www.kailing.pub;

import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;

/**
 * TRANSACTION_READ_COMMITTED 提交读隔离级别下的不可重复读问题
 * 验证步骤：验证步骤和RepeatableRead_Repeatable_Test一致
 * @author: kl @kailing.pub
 * @date: 2019/4/4
 */
public class ReadCommitted_Repeatable_Test extends BaseTest{
    /**
     * 事务A：
     * 事务A先去查找记录ID=30的记录，然后输出，此时记录数为0，然后等待事务B新增数据后再次查询，你会发现，输出记录数为1了
     *
     *
     */
    @Test
    public void testTxA() {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionStatus status = dataSourceTransactionManager.getTransaction(def);
        try {
            List list = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");
            System.out.println("第一次查询结果：" + list.size());
            List list1 = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");//此处断点，等待事务A提交新纪录
            System.out.println("第二次查询结果：" + list1.size());
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
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
            List list1 = jdbcTemplate.queryForList("SELECT * FROM user WHERE id = 31");//此处断点，等待事务A提交新纪录
            System.out.println("查询结果：" + list1.size());
            dataSourceTransactionManager.commit(status);
        } catch (Exception e) {
            System.err.println(e.fillInStackTrace().toString());
            dataSourceTransactionManager.rollback(status);
        }
    }

}
