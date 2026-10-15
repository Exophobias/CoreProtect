package net.coreprotect.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;

import net.coreprotect.utility.Chat;

/** Command permissions are checked here for documentation and independently by execution handlers. */
public class HelpCommand {
    private static final int PAGE_SIZE = 8;
    private static final String[][] STAFF = {
        {"inspect", "inspect [on|off]", "Inspect recorded block and container changes", "inspect"},
        {"lookup", "lookup <params>", "Search recorded changes", "lookup"},
        {"lookup.near", "near", "Look up nearby changes", "near"},
        {"rollback", "rollback <params>", "Preview or roll back recorded changes", "rollback"},
        {"restore", "restore <params>", "Restore rolled back changes", "restore"},
        {"restore", "undo", "Undo your last rollback or restore", "undo"},
        {"rollback|restore", "apply", "Apply your pending preview", "apply"},
        {"rollback|restore", "cancel", "Cancel your pending preview", "cancel"},
        {"teleport", "teleport <world> <x> <y> <z>", "Teleport to a lookup location", "teleport"},
        {"purge", "purge <params>", "Remove matching old history", "purge"},
        {"reload", "reload", "Reload operator configuration", "reload"},
        {"status", "status", "Show plugin and database status", "status"},
        {"give", "give <item>", "Give a logging tool", "give"},
        {"consumer", "consumer", "Show the consumer queue", "consumer"},
        {"networking", "network-debug", "Show networking diagnostics", "network-debug"}
    };
    private static final String[][] PARAMETERS = {
        {"users", "u:<users>", "Filter by users; separate names with commas"},
        {"time", "t:<time>", "Choose an age such as 1d, 2h or 30m"},
        {"radius", "r:<radius>", "Limit the radius; use a world name for a world filter"},
        {"action", "a:<action>", "Choose a permitted action type"},
        {"include", "i:<include>", "Include matching materials or entities"},
        {"exclude", "e:<exclude>", "Exclude matching materials or entities"},
        {"filter", "f:<text>", "Filter permitted chat, command or sign text"}
    };

    static boolean permitted(CommandSender sender, String permissions) {
        for (String node : permissions.split("\\|")) {
            if (sender.hasPermission("coreprotect." + node)) return true;
        }
        return false;
    }

    private static List<String[]> staff(CommandSender sender) {
        List<String[]> entries = new ArrayList<>();
        for (String[] entry : STAFF) if (permitted(sender, entry[0])) entries.add(entry);
        return entries;
    }

    private static boolean canQuery(CommandSender sender) {
        return permitted(sender, "lookup|rollback|restore");
    }

    private static boolean textFilter(CommandSender sender) {
        return permitted(sender, "rollback|restore|lookup.chat|lookup.command|lookup.sign");
    }

    static List<String> helpOptions(CommandSender sender) {
        List<String> options = new ArrayList<>();
        if (!permitted(sender, "help")) return options;
        int total = 1 + pages(staff(sender).size());
        for (int page = 1; page <= total; page++) options.add(Integer.toString(page));
        if (!staff(sender).isEmpty()) options.add("staff");
        for (String[] entry : staff(sender)) options.add(entry[3]);
        if (canQuery(sender)) {
            options.add("params");
            for (String[] parameter : PARAMETERS) {
                if (!parameter[0].equals("filter") || textFilter(sender)) options.add(parameter[0]);
            }
        }
        return options;
    }

    private static int pages(int entries) {
        return (entries + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private static void deny(CommandSender sender) {
        Chat.sendMessage(sender, "§cYou do not have permission to do that.");
    }

    private static void header(CommandSender sender, boolean staffPage, int page, int total) {
        Chat.sendMessage(sender, "§8---------- §aCoreProtect" + (staffPage ? " Staff" : "")
                + (total > 1 ? " §8(§f" + page + "§8/§f" + total + "§8)" : "") + " §8----------");
    }

    private static void row(CommandSender sender, String command, String description) {
        Chat.sendMessage(sender, "§a/co " + command + " §8- §7" + description);
    }

    private static void footer(CommandSender sender, int page, int total) {
        Chat.sendMessage(sender, page < total
                ? "§8---------- §a/co help " + (page + 1) + " §7for more §8----------"
                : "§8----------------------------------------");
    }

    protected static void runCommand(CommandSender sender, boolean permission, String[] args) {
        if (!permission || !permitted(sender, "help")) {
            deny(sender);
            return;
        }
        String requested = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "1";
        if (requested.equals("help")) requested = "1";
        if (requested.equals("rb") || requested.equals("ro")) requested = "rollback";
        if (requested.equals("rs") || requested.equals("re")) requested = "restore";
        if (requested.equals("l")) requested = "lookup";
        if (requested.equals("i") || requested.equals("inspector")) requested = "inspect";
        if (requested.equals("tp")) requested = "teleport";
        for (String[] entry : STAFF) {
            if (!entry[3].equals(requested)) continue;
            if (!permitted(sender, entry[0])) {
                deny(sender);
                return;
            }
            header(sender, true, 1, 1);
            row(sender, entry[1], entry[2]);
            footer(sender, 1, 1);
            return;
        }
        for (String[] parameter : PARAMETERS) {
            if (requested.equals(parameter[1].split(":")[0])) requested = parameter[0];
        }
        boolean parameterTopic = requested.equals("params") || requested.equals("parameters");
        for (String[] parameter : PARAMETERS) if (parameter[0].equals(requested)) parameterTopic = true;
        if (parameterTopic) {
            if (!canQuery(sender) || (requested.equals("filter") && !textFilter(sender))) {
                deny(sender);
                return;
            }
            String query = permitted(sender, "lookup") ? "lookup" : permitted(sender, "rollback") ? "rollback" : "restore";
            header(sender, true, 1, 1);
            for (String[] parameter : PARAMETERS) {
                if (parameter[0].equals("filter") && !textFilter(sender)) continue;
                if (requested.equals("params") || requested.equals("parameters") || requested.equals(parameter[0])) {
                    row(sender, query + " " + parameter[1], parameter[2]);
                }
            }
            footer(sender, 1, 1);
            return;
        }
        List<String[]> staff = staff(sender);
        int total = 1 + pages(staff.size());
        int page = 1;
        if (requested.equals("staff")) {
            if (staff.isEmpty()) {
                deny(sender);
                return;
            }
            page = 2;
        } else {
            try {
                page = Integer.parseInt(requested);
            } catch (NumberFormatException invalid) {
                Chat.sendMessage(sender, "§cInvalid help page or topic.");
            }
        }
        page = Math.max(1, Math.min(total, page));
        header(sender, page > 1, page, total);
        if (page == 1) {
            row(sender, "help [page|topic]", "Show permitted commands and parameters");
        } else {
            int offset = (page - 2) * PAGE_SIZE;
            for (int index = offset; index < Math.min(offset + PAGE_SIZE, staff.size()); index++) {
                String[] entry = staff.get(index);
                row(sender, entry[1], entry[2]);
            }
        }
        footer(sender, page, total);
    }
}
