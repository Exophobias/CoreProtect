package net.coreprotect.command;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Database;
import net.coreprotect.thread.NetworkHandler;
import net.coreprotect.utility.Chat;
import net.coreprotect.language.Language;

class ReloadConfigurationFailureTest {
    @Test void rejectedReloadKeepsDatabaseClientsWorkersAndPersistenceAdmission() throws Exception {
        Language.loadPhrases();
        try (var consumer = mockStatic(Consumer.class); var database = mockStatic(Database.class);
             var config = mockStatic(ConfigHandler.class); var network = mockStatic(NetworkHandler.class); var chat = mockStatic(Chat.class)) {
            consumer.when(Consumer::beginDatabaseReload).thenReturn(Consumer.OperationStartResult.STARTED);
            consumer.when(() -> Consumer.lockDatabaseReload(anyLong())).thenReturn(true);
            database.when(() -> Database.awaitConnectionDrain(anyLong())).thenReturn(true);
            config.when(ConfigHandler::reloadConfigurationSafely).thenThrow(new IOException("invalid operator input"));
            assertFalse(ReloadCommand.performReload(mock(CommandSender.class)));
            consumer.verify(() -> Consumer.endDatabaseReload(true));
            database.verify(Database::closeConnection, never());
            config.verify(() -> ConfigHandler.performInitialization(false), never());
            network.verifyNoInteractions();
        }
    }
}
