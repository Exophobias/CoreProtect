package net.coreprotect.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class UpstreamHelpCompatibilityTest {
    @Test void aliasLabelAndCoordinateTopicKeepPermissionFilteringAndEightRowLimit() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenAnswer(call -> Set.of("coreprotect.help", "coreprotect.lookup", "coreprotect.lookup.chat").contains(call.getArgument(0)));
        List<String> lines = new ArrayList<>();
        doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
        HelpCommand.runCommand(sender, true, "coreprotect", new String[]{"help", "params"});
        assertEquals(8, lines.stream().filter(line -> line.startsWith("§a/coreprotect")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("c:<x,y,z>")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("/co ")));
        lines.clear();
        HelpCommand.runCommand(sender, true, "coreprotect", new String[]{"help", "coord:"});
        assertTrue(lines.stream().anyMatch(line -> line.contains("c:<x,y,z>")));
        when(sender.hasPermission("coreprotect.lookup")).thenReturn(false);
        lines.clear();
        HelpCommand.runCommand(sender, true, "coreprotect", new String[]{"help", "coord:"});
        assertEquals(List.of("§cYou do not have permission to do that."), lines);
    }
}
