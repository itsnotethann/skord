# skord

Discord bots in pure Skript. skord is a standalone host for the platform-free Skript engine
[uncommon-skript](https://github.com/itsnotethann/uncommon-skript): one jar, a token and your
scripts. No Minecraft server involved.

```
on message received:
	if content of event-message is "!ping":
		reply with "Pong! %gateway ping% ms"
```

Early days: phase 2 of [PLAN.md](PLAN.md). Slash commands work; buttons and modals come next.

```
slash command ticket:
	description: Open a support ticket
	guild only: true
	trigger:
		reply with "Opening a ticket..." and store it in {_reply}
		create text channel named "ticket-%user's name%" in category {ticket-category} and store it in {_ch}
		send "Hi %user's mention%, describe your problem." to {_ch}
		edit {_reply} to "Ticket opened: %{_ch}'s mention%"
```

Every Discord request pauses the trigger until Discord answers. No thread blocks, and your
locals are still there afterwards. You never write callbacks or `await`.

## Slash commands

```
slash command ban:
	description: Ban a member
	permission: ban members
	guild only: true
	arguments:
		member: member  "Who to ban"
		reason: text?   "Why"
	trigger:
		reply ephemerally with "Banning %arg-member%..."
```

- Argument types: `text`, `integer`, `number`, `boolean`, `user`, `member`, `role`, `channel`,
  `category`. A `?` makes one optional. Read them with `arg-<name>`.
- `slash command ticket close:` is a subcommand of `ticket`.
- `on slash command "ping":` works too, and registers a bare `/ping` if no structure declares it.
- skord registers commands when scripts load, and only sends them to Discord when they changed.
  Reloading a script that didn't touch a command sends nothing.
- While developing, set `commands: dev-guild:` in `skord.yml` to a test guild's ID. Guild
  commands update instantly.

**Replying.** Discord fails a command that isn't answered within 3 seconds; skord handles that.
If your trigger pauses (for a request, or a `wait`) before it replies, skord shows "thinking..."
and your reply replaces it. If the script thread is busy for 2.5 seconds, skord does the same.
Use `defer reply [ephemerally]` to choose yourself. A second `reply` sends a follow-up.

**Errors.** A failed request doesn't stop the trigger. `last discord error` holds why, until the
next request succeeds. If your script never reads it, failures are logged with the script and
line. Shown as text it's Discord's reason; it also has parts you can check:

```
if last discord error is set:
	set {_error} to last discord error
	if error type of {_error} is missing permission:
		reply with "I need %missing permission of {_error}% for that."
```

- `error type of`: `missing permission`, `missing access`, `hierarchy` (the bot's role is too
  low), `unknown channel`, `unknown message`, `unknown member`, `unknown role`, `unknown user`,
  `invalid input`, `timed out` or `other`.
- `missing permission of`: a permission such as `manage channels`. Set when skord's own check
  caught it before sending; unset when Discord refused, since Discord doesn't say which one.
- `error code of`: Discord's numeric code (`50013`), for anything the types don't name.
- `error message of`: the reason as text, the same as `%{_error}%`.

Add `without waiting` to any request to send it without pausing:
`send "Logged." to {log-channel} without waiting`.

See `core/src/main/resources/skord/examples/` for complete scripts.

## Running a bot

You need Java 25.

1. Create an application at <https://discord.com/developers/applications>. Under **Bot**,
   click **Reset Token** and copy the token. Under **Privileged Gateway Intents**, turn on
   **Message Content Intent**.
2. Invite the bot to your server: **OAuth2 → URL Generator**, tick `bot` and
   `applications.commands` (slash commands need it), then the permissions it needs (at least
   View Channels, Send Messages, Read Message History; the ticket example also needs Manage
   Channels).
3. Put `skord.jar` in an empty folder and start it, by double-clicking it or with:
   ```
   java -jar skord.jar
   ```
   A double-click opens skord in its own console window, in the jar's folder. The first start
   creates `skord.yml` and an example script, then stops.
4. Paste your token into `token:` in `skord.yml` and start skord again.

Scripts go in `Skript/scripts/`. Keep `skord.yml` private: anyone with the token controls your
bot. On a host that keeps secrets out of files, set the `SKORD_TOKEN` environment variable
instead; it overrides the file.

## Console

| Command | Does |
|---|---|
| `sk reload all` | reload `config.sk` and every script |
| `sk reload <script>` | reload one script, or every script in a folder |
| `sk reload config` | reload `config.sk` |
| `sk enable <script>` / `sk disable <script>` | turn a script on or off |
| `sk list` | show loaded and disabled scripts |
| `stop` | shut down, saving variables |

The `sk` prefix is optional.

`java -jar skord.jar --check` loads every script without connecting to Discord, and exits
non-zero on any error. It's useful before deploying, or in CI.

## Building

```
./gradlew :core:shadowJar
```

The jar lands in `core/build/libs/skord.jar`. With an `uncommon` checkout next to this one, the
engine is built from source; otherwise it comes from JitPack.

```
./gradlew :testbot:botTest
```

runs the test scripts in `testbot/scripts/` against a fake Discord: no token, no network. Each
`x.sk` has an `x.expected` listing what it should record, in order.

`./gradlew :core:run` runs a bot in `run/` for development.
