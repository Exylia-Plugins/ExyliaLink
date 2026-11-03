package net.exylia.exyliaLink.config;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * What the plugin says, {@code lang/<code>/messages.yml}. English here, every other language in
 * the jar's {@code lang/} folder.
 */
public record LinkMessages(
        @Comment("Starts every chat line of this plugin as %prefix%.")
        String prefix,
        Link link,
        Errors errors,
        Admin admin,
        Import importing,
        Requirements requirements) {

    /** A spacer line: a blank one is not sent at all. */
    private static final String GAP = "&r";
    private static final String OK = "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.4]";
    private static final String NO = "[sound:ENTITY_VILLAGER_NO|0.6|1.0]";

    public LinkMessages() {
        this("{primary}&lEXYLIA LINK {letters_black}•<reset>", new Link(), new Errors(), new Admin(), new Import(), new Requirements());
    }

    public record Link(
            @Comment("/link: the code to use in Discord. %code%, %url% (the network's link page), %time%.")
            List<String> code,
            @Comment("/link <code> worked. %discord% is the Discord username.")
            String redeemed,
            @Comment("Sent when the account becomes linked from anywhere else (Discord, the web).")
            String linked,
            @Comment("Sent when the account is unlinked from anywhere.")
            String unlinked,
            @Comment("/link status. %discord%, %discord_id%.")
            List<String> statusLinked,
            List<String> statusUnlinked,
            @Comment("/unlink asks first. %discord%.")
            String unlinkConfirm,
            String unlinkDone,
            String notLinked) {

        public Link() {
            this(List.of(
                            "&r",
                            "[center]{primary}&lLINK YOUR DISCORD",
                            "&r",
                            " {letters_black}▎ {letters}Your code {letters_black}» <click:copy_to_clipboard:%code%><hover:show_text:'{letters}Click to copy'>{highlight}&l%code%</hover></click> {muted}(click to copy)",
                            " {letters_black}▎ {letters}Run {info}/link %code% {letters}in our Discord",
                            " {letters_black}▎ {letters}or enter it at <click:open_url:%url%><hover:show_text:'{letters}Open the link page'>{info}%url%</hover></click>",
                            "&r",
                            " {letters_black}▎ {letters}Expires in {info}%time% ⌚",
                            "&r"),
                    OK + "%prefix% {success}Linked to {highlight}%discord%{success}. Welcome aboard!",
                    OK + "%prefix% {success}Your account is now linked to {highlight}%discord%{success}.",
                    "[sound:BLOCK_NOTE_BLOCK_BASS|0.5|0.8]%prefix% {letters}Your account is no longer linked to Discord.",
                    List.of(
                            "&r",
                            "[center]{primary}&lDISCORD LINK",
                            "&r",
                            " {letters_black}▎ {letters}Status {letters_black}» {success}Linked",
                            " {letters_black}▎ {letters}Discord {letters_black}» {highlight}%discord%",
                            "&r",
                            "{warning}➥ {letters}Run {info}/unlink {letters}to remove it",
                            "&r"),
                    List.of(
                            "&r",
                            "[center]{primary}&lDISCORD LINK",
                            "&r",
                            " {letters_black}▎ {letters}Status {letters_black}» {muted}Not linked",
                            "&r",
                            "{warning}➥ {letters}Run {info}/link {letters}to get a code",
                            "&r"),
                    "{letters}Unlink your account from {highlight}%discord%{letters}?",
                    OK + "%prefix% {success}Your account is no longer linked.",
                    NO + "%prefix% {letters}Your account is not linked. Run {info}/link {letters}to link it.");
        }
    }

    public record Errors(
            @Comment("The connection to Exylia Link is down or did not answer in time.")
            String offline,
            @Comment("%time% is how long to wait.")
            String rateLimited,
            String cooldown,
            String alreadyLinked,
            String uuidTaken,
            String discordTaken,
            String codeInvalid,
            String selfUnlinkDisabled,
            @Comment("%error% is the gateway's error code.")
            String internal) {

        public Errors() {
            this(NO + "%prefix% {error}Discord linking is unavailable right now. {letters}Try again in a moment.",
                    NO + "%prefix% {letters}Slow down, try again in {info}%time% ⌚{letters}.",
                    NO + "%prefix% {letters}You can link again in {info}%time% ⌚{letters}.",
                    NO + "%prefix% {letters}You are already linked. Run {info}/unlink {letters}first.",
                    NO + "%prefix% {letters}You are already linked. Run {info}/unlink {letters}first.",
                    NO + "%prefix% {error}That Discord account is already linked {letters}to another Minecraft account.",
                    NO + "%prefix% {error}That code is not valid. {letters}Run {info}/link {letters}in Discord for a new one.",
                    NO + "%prefix% {letters}Unlinking is disabled on this network. Ask a staff member.",
                    NO + "%prefix% {error}Something went wrong {muted}(%error%){error}. {letters}Try again later.");
        }
    }

    public record Admin(
            @Comment("/linkadmin connect. %code%, %url%, %time%.")
            List<String> connectCode,
            String connectWaiting,
            @Comment("%network% is the network's name.")
            String connectDone,
            String connectExpired,
            String connectFailed,
            @Comment("/linkadmin status. %state%, %network%, %server%, %latency%, %rewards%, %error%.")
            List<String> status,
            String stateConnected,
            String stateConnecting,
            String stateNotLinked,
            String stateStopped,
            String none,
            String disconnectConfirm,
            String disconnected,
            String notConnected,
            @Comment("/linkadmin unlink <player>. %player%.")
            String unlinkDone,
            String unlinkNone,
            String playerNotFound,
            @Comment("Told to online admins when the network's secret was revoked on the web.")
            String secretRevoked,
            @Comment("Told to online admins when Exylia Link no longer accepts this plugin version.")
            String protocolRefused) {

        public Admin() {
            this(List.of(
                            "&r",
                            "[center]{primary}&lCONNECT TO EXYLIA LINK",
                            "&r",
                            " {letters_black}▎ {letters}Code {letters_black}» <click:copy_to_clipboard:%code%><hover:show_text:'{letters}Click to copy'>{highlight}&l%code%</hover></click>",
                            " {letters_black}▎ {letters}Approve it at <click:open_url:%url%><hover:show_text:'{letters}Open the approval page'>{info}%url%</hover></click>",
                            " {letters_black}▎ {letters}Expires in {info}%time% ⌚",
                            "&r",
                            "{warning}➥ {letters}Sign in with Discord, pick the network and approve",
                            "&r"),
                    NO + "%prefix% {letters}A connection code is already waiting for approval.",
                    OK + "%prefix% {success}Connected to {highlight}%network%{success}. Every server of the network picks it up within 30s.",
                    NO + "%prefix% {letters}The code expired. Run {info}/linkadmin connect {letters}again.",
                    NO + "%prefix% {error}Could not reach Exylia Link. {letters}Try again in a moment.",
                    List.of(
                            "&r",
                            "[center]{primary}&lEXYLIA LINK",
                            "&r",
                            " {letters_black}▎ {letters}Connection {letters_black}» %state%",
                            " {letters_black}▎ {letters}Network {letters_black}» {highlight}%network%",
                            " {letters_black}▎ {letters}Server id {letters_black}» {info}%server%",
                            " {letters_black}▎ {letters}Latency {letters_black}» {info}%latency%",
                            " {letters_black}▎ {letters}Rewards in progress {letters_black}» {info}%rewards%",
                            " {letters_black}▎ {letters}Last error {letters_black}» {muted}%error%",
                            "&r"),
                    "{success}Connected",
                    "{warning}Connecting…",
                    "{muted}Not linked",
                    "{error}Update ExyliaLink",
                    "—",
                    "{letters}Disconnect {highlight}every server {letters}of this network from Exylia Link?",
                    OK + "%prefix% {success}Disconnected. {letters}Every server drops the link within 30s.",
                    NO + "%prefix% {letters}This network is not connected. Run {info}/linkadmin connect{letters}.",
                    OK + "%prefix% {success}Unlinked {highlight}%player%{success}.",
                    NO + "%prefix% {highlight}%player% {letters}was not linked.",
                    NO + "%prefix% {error}No player called {highlight}%player%{error}.",
                    NO + "%prefix% {error}Exylia Link revoked this network's secret. {letters}Run {info}/linkadmin connect {letters}to link it again.",
                    NO + "%prefix% {error}Exylia Link no longer accepts this ExyliaLink version. {letters}Update the plugin.");
        }
    }

    public record Import(
            String running,
            String none,
            @Comment("%error% is what went wrong.")
            String unreadable,
            @Comment("%total%, %source%.")
            String started,
            @Comment("%sent%, %total%, %inserted%, %skipped%, %conflicts%.")
            String progress,
            List<String> done,
            String failed) {

        public Import() {
            this(NO + "%prefix% {letters}An import is already running.",
                    NO + "%prefix% {letters}No DiscordSRV links found {muted}(no running DiscordSRV, accounts.aof or SQL storage){letters}.",
                    NO + "%prefix% {error}Could not read DiscordSRV's links: {muted}%error%",
                    "%prefix% {letters}Importing {info}%total% {letters}links from {highlight}%source%{letters}…",
                    "%prefix% {letters}Import {info}%sent%{letters_black}/{info}%total% {letters_black}» {success}%inserted% new {letters_black}· {muted}%skipped% skipped {letters_black}· {warning}%conflicts% conflicts",
                    List.of(
                            "&r",
                            "[sound:ENTITY_EXPERIENCE_ORB_PICKUP|0.6|1.4;center]{primary}&lDISCORDSRV IMPORT DONE",
                            "&r",
                            " {letters_black}▎ {letters}Links read {letters_black}» {info}%total%",
                            " {letters_black}▎ {letters}Imported {letters_black}» {success}%inserted%",
                            " {letters_black}▎ {letters}Already linked {letters_black}» {muted}%skipped%",
                            " {letters_black}▎ {letters}Conflicts {letters_black}» {warning}%conflicts%",
                            "&r",
                            "{warning}➥ {letters}Conflicts are listed on the dashboard and in the console",
                            "&r"),
                    NO + "%prefix% {error}The import stopped at {info}%sent%{letters_black}/{info}%total% {muted}(%error%){error}. {letters}Running it again is safe.");
        }
    }

    public record Requirements(
            @Comment("Why the player must link, when the network set no message of its own (%reason%).")
            String reason,
            @Comment("Sent once the player is known to need a link. %reason%.")
            List<String> warning,
            @Comment("The same in restrict mode, where some commands stay locked until they link.")
            List<String> warningRestricted,
            @Comment("Repeated every requirements-reminder-minutes (config.yml) until they link.")
            String reminder,
            @Comment("A locked command was refused.")
            String blocked) {

        public Requirements() {
            this("Your rank requires a linked Discord account.",
                    List.of(
                            "&r",
                            "[sound:BLOCK_NOTE_BLOCK_PLING|0.5|1.2;center]{primary}&lLINK REQUIRED",
                            "&r",
                            " {letters_black}▎ {letters}%reason%",
                            "&r",
                            "<click:run_command:/link><hover:show_text:'{letters}Click to get your code'>{warning}➥ {letters}Click here or run {info}/link {letters}to link your Discord</hover></click>",
                            "&r"),
                    List.of(
                            "&r",
                            "[sound:BLOCK_NOTE_BLOCK_PLING|0.5|1.2;center]{primary}&lLINK REQUIRED",
                            "&r",
                            " {letters_black}▎ {letters}%reason%",
                            " {letters_black}▎ {letters}Some commands stay {error}locked {letters}until you link.",
                            "&r",
                            "<click:run_command:/link><hover:show_text:'{letters}Click to get your code'>{warning}➥ {letters}Click here or run {info}/link {letters}to link your Discord</hover></click>",
                            "&r"),
                    "%prefix% {letters}%reason% <click:run_command:/link><hover:show_text:'{letters}Click to get your code'>{info}&n/link</hover></click>",
                    NO + "%prefix% {error}Link your Discord account first. <click:run_command:/link><hover:show_text:'{letters}Click to get your code'>{letters}Run {info}/link {letters}to unlock this command.</hover></click>");
        }
    }
}
