package com.tempoapi.util;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AfterCommitTest {
    private TransactionTemplate transaction(Connection connection) throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void notifiesOnlyAfterCommit() throws Exception {
        Connection connection = mock(Connection.class);
        AtomicInteger notifications = new AtomicInteger();
        transaction(connection).execute(status -> {
            AfterCommit.run(notifications::incrementAndGet);
            assertThat(notifications).hasValue(0);
            return null;
        });
        verify(connection).commit();
        assertThat(notifications).hasValue(1);
    }

    @Test
    void rollbackSuppressesNotifications() throws Exception {
        Connection connection = mock(Connection.class);
        AtomicInteger notifications = new AtomicInteger();
        TransactionTemplate transaction = transaction(connection);
        assertThatThrownBy(() -> transaction.execute(status -> {
            AfterCommit.run(notifications::incrementAndGet);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertThat(notifications).hasValue(0);
    }

    @Test
    void notificationFailureDoesNotChangeCommittedResult() throws Exception {
        Connection connection = mock(Connection.class);
        String result = transaction(connection).execute(status -> {
            AfterCommit.run(() -> { throw new IllegalStateException("transport unavailable"); });
            return "confirmed";
        });
        assertThat(result).isEqualTo("confirmed");
        verify(connection).commit();
    }
}
