
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.execution;

import io.prestosql.Session;
import io.prestosql.Session.SessionBuilder;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AccessControlConfig;
import io.prestosql.security.AccessControlManager;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.sql.tree.Commit;
import io.prestosql.transaction.TransactionId;
import io.prestosql.transaction.TransactionManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.prestosql.spi.StandardErrorCode.NOT_IN_TRANSACTION;
import static io.prestosql.spi.StandardErrorCode.UNKNOWN_TRANSACTION;
import static io.prestosql.testing.TestingEventListenerManager.emptyEventListenerManager;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static io.prestosql.testing.assertions.PrestoExceptionAssert.assertPrestoExceptionThrownBy;
import static io.prestosql.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestCommitTask
{
    private final Metadata metadata = createTestMetadataManager();
    private final ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("stage-executor-%s"));

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
    }

    @Test
    public void testCommit()
    {
        TransactionManager transactionManager = createTestTransactionManager();

        Session session = sessionBuilder()
                .setTransactionId(transactionManager.beginTransaction(false))
                .build();
        QueryStateMachine stateMachine = createQueryStateMachine("COMMIT", session, transactionManager);
        assertTrue(stateMachine.getSession().getTransactionId().isPresent());
        assertEquals(transactionManager.getAllTransactionInfos().size(), 1);

        getFutureValue(new CommitTask().execute(new Commit(), transactionManager, metadata, new AllowAllAccessControl(), stateMachine, emptyList(), WarningCollector.NOOP));
        assertTrue(stateMachine.getQueryInfo(Optional.empty()).isClearTransactionId());
        assertFalse(stateMachine.getQueryInfo(Optional.empty()).getStartedTransactionId().isPresent());

        assertTrue(transactionManager.getAllTransactionInfos().isEmpty());
    }

    @Test
    public void testNoTransactionCommit()
    {
        TransactionManager transactionManager = createTestTransactionManager();

        Session session = sessionBuilder()
                .build();
        QueryStateMachine stateMachine = createQueryStateMachine("COMMIT", session, transactionManager);

        assertPrestoExceptionThrownBy(
                () -> getFutureValue(new CommitTask().execute(new Commit(), transactionManager, metadata, new AllowAllAccessControl(), stateMachine, emptyList(), WarningCollector.NOOP)))
                .hasErrorCode(NOT_IN_TRANSACTION);

        assertFalse(stateMachine.getQueryInfo(Optional.empty()).isClearTransactionId());
        assertFalse(stateMachine.getQueryInfo(Optional.empty()).getStartedTransactionId().isPresent());

        assertTrue(transactionManager.getAllTransactionInfos().isEmpty());
    }

    @Test
    public void testUnknownTransactionCommit()
    {
        TransactionManager transactionManager = createTestTransactionManager();

        Session session = sessionBuilder()
                .setTransactionId(TransactionId.create()) // Use a random transaction ID that is unknown to the system
                .build();
        QueryStateMachine stateMachine = createQueryStateMachine("COMMIT", session, transactionManager);

        Future<?> future = new CommitTask().execute(new Commit(), transactionManager, metadata, new AllowAllAccessControl(), stateMachine, emptyList(), WarningCollector.NOOP);
        assertPrestoExceptionThrownBy(() -> getFutureValue(future))
                .hasErrorCode(UNKNOWN_TRANSACTION);

        assertTrue(stateMachine.getQueryInfo(Optional.empty()).isClearTransactionId()); // Still issue clear signal
        assertFalse(stateMachine.getQueryInfo(Optional.empty()).getStartedTransactionId().isPresent());

        assertTrue(transactionManager.getAllTransactionInfos().isEmpty());
    }

    private QueryStateMachine createQueryStateMachine(String query, Session session, TransactionManager transactionManager)
    {
        return QueryStateMachine.begin(
                query,
                Optional.empty(),
                session,
                URI.create("fake://uri"),
                new ResourceGroupId("test"),
                true,
                transactionManager,
                new AccessControlManager(transactionManager, emptyEventListenerManager(), new AccessControlConfig()),
                executor,
                metadata,
                WarningCollector.NOOP,
                Optional.empty());
    }

    private static SessionBuilder sessionBuilder()
    {
        return testSessionBuilder()
                .setCatalog("tpch")
                .setSchema(TINY_SCHEMA_NAME);
    }
}
