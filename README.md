# skord

Discord bots in pure Skript. skord is a standalone host for the platform-free Skript engine
[uncommon-skript](https://github.com/itsnotethann/uncommon-skript): one jar, a token and your
scripts. No Minecraft server needed, though skord can also be the Discord side of a Minecraft
network (see [Game servers](#game-servers)).

```
on message received:
	if content of event-message is "!ping":
		reply with "Pong! %gateway ping% ms"
```

Early days: `0.1.0`, not released yet. Slash commands, buttons, select menus, modals, embeds and
linked game servers work. Role and member management come next.

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
  `invalid input`, `timed out`, `offline`, `no such function`, `error`, `out of scope` or `other`.
- `missing permission of`: a permission such as `manage channels`. Set when skord's own check
  caught it before sending; unset when Discord refused, since Discord doesn't say which one.
- `error code of`: Discord's numeric code (`50013`), for anything the types don't name.
- `error message of`: the reason as text, the same as `%{_error}%`.

Add `without waiting` to any request to send it without pausing:
`send "Logged." to {log-channel} without waiting`.

## Buttons

```
slash command rules:
	description: Show the rules
	trigger:
		set {_ok} to a success button with id "rules:agree" labeled "I agree"
		set {_read} to a link button to "https://example.com/rules" labeled "Read them"
		reply ephemerally with "Read the rules, then click:" with components {_ok} and {_read}

on button click "rules:agree":
	edit the message to "Thanks!" with no components
```

- Styles are `primary` (the default), `secondary`, `success` and `danger`. Any button can be
  `disabled`. A link button opens its URL and sends no click.
- `send "..." with components ... to {_channel}` puts buttons on a plain message.
- `on button click starting with "shop:"` matches a prefix. `component id` is the whole id, and
  `component id parts` is the id split on `:`, so `shop:buy:42` gives `42` as `{_parts::3}`.
- In a click, `edit the message to "..."` changes the message the button is on: `with components
  ...` replaces the buttons, `with no components` removes them. A click nothing answers is
  acknowledged without a change.
- `wait for a button click on {_msg} by {_user} within 60 seconds and store it in {_id}` pauses
  until someone clicks and stores the component id. If the time runs out, `{_id}` is not set.
- A click goes back to the process that sent the message, so a game server that sends a button
  handles its click.

### Select menus

```
slash command roles:
	description: Pick your roles
	trigger:
		set {_menu} to a select menu with id "roles" with placeholder "Pick up to two" allowing up to 2 choices
		add option "Red team" with value "red" described as "Fights on the left" to {_menu}
		add option "Blue team" with value "blue" with emoji "🔵" to {_menu}
		add default option "Spectator" with value "spectate" to {_menu}
		reply ephemerally with "Choose:" with components {_menu}

on select menu "roles":
	set {_picked::*} to selected values
	edit the message to "You picked %{_picked::*}%" with no components
```

- A menu goes in the same `with components ...` slot as buttons, and they mix:
  `with components {_menu} and {_done}`. Each menu takes a row of its own; buttons fill rows of five.
- Without `allowing`, one option is picked. `allowing up to 3 choices` lets someone pick one to
  three, and `allowing 0 to 3 choices` also lets them pick none. The most is capped at the number of
  options. `a disabled select menu ...` can't be used.
- `add option "Label" with value "v" to {_menu}` adds an option; `described as "..."` adds a line of
  text under it, `with emoji "..."` an emoji (a plain one, or a custom one written `<:name:id>`), and
  `add default option ...` makes it picked to begin with. A menu holds 25 options at most.
- `on select menu starting with "shop:"`, `component id`, `component id parts` and `edit the message
  to` work as they do for buttons. `selected values` is the list of picked values, `selected value`
  the first one.
- `wait for a selection on {_msg} by {_user} within 60 seconds and store it in {_values::*}` pauses
  until someone picks and stores the picked values. If the time runs out, or nothing was picked,
  `{_values::*}` is not set.
- A selection goes back to the process that sent the message, like a click.

## Modals

```
slash command report:
	description: Report a player
	trigger:
		set {_modal} to a modal with id "report" titled "Report a player"
		add text input with id "who" labeled "Who" with placeholder "Their name" with max length 32 to {_modal}
		add paragraph text input with id "details" labeled "What happened" with min length 10 to {_modal}
		add text input with id "proof" labeled "Proof link" optional to {_modal}
		show {_modal} to the user

on modal submit "report":
	send "%mention of event-user% reported %input "who"%: %input "details"%" to {staff-channel}
	reply ephemerally with "Thanks, we got your report."
```

- `a modal with id "x" titled "Y"` makes a modal. The title is at most 45 characters.
- `add [short|paragraph] text input with id "x" labeled "Y" to {_modal}` adds an input. A short input
  is one line, a paragraph several. After the label, in this order and each optional:
  `with placeholder "..."` (grey text shown while it's empty), `with value "..."` (filled in to begin
  with), `with min length 10`, `with max length 500` (1 to 4000), and `optional`. Inputs are required
  unless marked `optional`. A modal holds five inputs, and a label is at most 45 characters. An input
  Discord would refuse is left out, with a warning in the console.
- `show {_modal} to the user` (or `open modal {_modal}`) answers a slash command, a button click or a
  selection by opening the modal. It has to be the **first** answer: after `reply`, `defer the reply`,
  or a `wait` long enough for skord to defer by itself (about 2.5 seconds), it fails, and
  `last discord error` says why. So show the modal before anything that waits.
- `on modal submit "report"` runs when someone sends the form; `on modal submit starting with
  "vote:"` matches by prefix, and `component id` and `component id parts` read the modal's id, as for
  buttons. `value of input "who"` (or just `input "who"`) is what they typed. An optional input left
  empty is not set.
- Answer a submit with `reply with`, `reply ephemerally with` or `defer the reply`. When the modal was
  opened from a button or a menu, `edit the message to ...` edits that message instead. A submit that
  nothing answers closes the form without a message.
- On a linked game server, a modal works the same way: the submit goes back to the server that
  showed it.
- There is no `wait for a modal submit`. The submit is a new interaction that has to be answered
  within three seconds, and only its own `on modal submit` handler can answer it, so handle it there.

## Embeds

```
slash command rules:
	description: Show the rules
	trigger:
		set {_e} to a new embed titled "Server rules"
		set description of {_e} to "Be kind."
		set colour of {_e} to "blurple"
		add inline field "Rule 1" with value "No spam" to {_e}
		add inline field "Rule 2" with value "No ads" to {_e}
		set footer of {_e} to "Updated weekly"
		reply with "Please read:" with embed {_e}
```

- `a new embed [titled "..."]` makes one. Its parts are `title`, `description`, `url`, `colour`,
  `author name`, `author icon`, `author url`, `footer`, `footer icon`, `image`, `thumbnail` and
  `timestamp`: set them with `set description of {_e} to ...`, read them back the same way.
- A colour is a hex text (`"#5865F2"`), a name (`red`, `blue`, `blurple`, ...) or a number.
- `add [inline] field "Name" with value "..." to {_e}` adds a field.
- Embeds go after the text: `send "..." with embed {_e} to {_ch}`, or alone with
  `send embed {_e} to {_ch}`. Several are `with embeds {_a} and {_b}`, and they mix with buttons:
  `with embed {_e} with components {_ok}`. `reply` and `edit` take the same forms.
- `edit ... with no embeds` removes them. `embeds of {_msg}` reads a message's embeds.
- A message whose embeds break one of Discord's limits is not sent, and the error names the limit.

## Game servers

skord can be the Discord side of a Minecraft network. The bot keeps the token, and each game
server connects to it over a WebSocket. A script on a server uses the same Discord syntax as a
script on the bot, and where the script lives decides where it runs: a `slash command` in a
server's script is registered through the bot and runs on that server, and its `reply with` is
carried out by the bot.

The server owns the link between a Discord account and a player. skord only caches it.

### Linking a server

1. Turn the bridge on in the bot's `skord.yml` and restart it:
   ```yaml
   bridge:
     port: 18765
     bind: 127.0.0.1
   ```
   `bind` defaults to `0.0.0.0`. If servers connect from other machines, put the port behind TLS
   (a reverse proxy serving `wss://`).
2. On the bot's console, create the link:
   ```
   sk link create survival --allow send,reply,call
   ```
   It prints the secret once. skord keeps only its SHA-256, in `.skord/links.yml`.
3. On the server, install the platform jar and fill in its `config.yml`:
   ```yaml
   mode: remote
   url: ws://127.0.0.1:18765
   name: survival
   secret: "<the secret>"
   timeout: 5
   meta:
     region: eu
   ```
   `meta` is optional: text the server tells the bot when it connects, such as its version.
   `sk link list` shows it under the link. The bot keeps at most 16 entries of 128 characters.
   - **Minestom** (skript-minestom): `bridge-minestom` loads as a Skript addon. Scripts get the full
     Discord syntax.
   - **Paper**: `bridge-paper` registers the Java API (below) with Bukkit's `ServicesManager`.
     There is no Skript syntax on Paper yet.

`sk link list` shows every link, whether it is connected, what it may do, and its `meta`.
`sk link revoke survival` cuts a server off and forgets its secret.

### Scopes

The bot decides what each server may do. `--allow` on `sk link create` or `sk link scope` sets it,
and a change applies at once, to a connected server too.

| `--allow` | Lets the server |
|---|---|
| `send` | send messages |
| `reply` | reply to messages |
| `edit` | edit messages |
| `delete` | delete messages |
| `channels` | create channels |
| `read` | fetch messages and other Discord objects, read the gateway ping |
| `call` | call shared functions on the bot and on other servers |
| `roles`, `members` | reserved; nothing uses them yet |

A request outside the scope fails with `error type` `out of scope`, and `missing scope of` names
it. Answering an interaction for the server's own command needs no scope.

`--listen` sets which Discord events the bot forwards: `messages`, `boosts`. The words `members`,
`invites` and `components` are accepted but no event uses them yet. Button, menu and modal
interactions need no listen word: they go to whichever process sent the message.

### Commands and events on a server

- A server sends its commands when it connects and after every reload, and the bot syncs them to
  Discord with its own. The bot saves them in `.skord/servers/<name>.json`, so a bot restart
  doesn't drop them.
- Several servers may declare the same command if the declarations are identical. The bot routes
  it to the server where the user's linked player is online, else the first online server in
  `bridge.route-order`. If no owner is connected, the user gets an ephemeral "server offline" reply.
- A server subscribes to the events its scripts handle. When both the bot and a server handle an
  event, both run, in no fixed order. `cancel event` doesn't cross processes.
- Discord values on a server are references: an id and a name. Anything more is a fetch through the
  bot, and pauses like any other request.

**Held events.** A Java consumer can subscribe to a boost with `held`. While the server is offline,
the bot queues the event in `.skord/held/<server>.jsonl`, and delivers the queue in order when the
server reconnects. Delivery is at least once: a server that dies before acknowledging sees the event
again, with `event is redelivered` true. Pay out on `event id` in your own storage, so a second run
does nothing. `bridge.held.max-events` (1000) and `bridge.held.max-age` (7 days) cap the queue.
Boosts also need `guild-members` in `intents`.

### Presence

- On the bot: `on server connect`, `on server disconnect`, `event-server`, `event-time`,
  `server "survival" is online`, `online servers`, `all linked servers`,
  `last seen of server "survival"`.
- On a server: `on bot connect`, `on bot disconnect`, `the bot is online`.

### Shared functions

A game server linked to the bot can call the bot, the bot can call it, and servers can call each
other, with a function declared on one side:

```
shared function onlinePlayers() :: texts:
	return "Ada" and "Grace"
```

and called from the other:

```
onlinePlayers() from "survival" and store it in {_players::*}
onlinePlayers() from "survival" without waiting
onlinePlayers() from all servers and store it in {_players::*}
```

- Parameters and results are text, numbers, booleans, Discord objects, or lists of those. Anything
  else is a load error.
- A call pauses like any request. `from "bot"` (on a server) is the bot; `from "<other server>"` goes
  through the bot, and needs the `call` scope on the calling server's link.
- `from all servers` waits until every connected server has answered or timed out, and stores one
  answer per server under its name: `{_players::survival::1}`. `the servers that failed in the last
  call` lists the ones that didn't answer, and `last request error` holds the first of their errors.
- A failure lands in `last request error` (also written `last discord error`), with `error type of`
  `timed out`, `offline`, `no such function`, `error` or `out of scope`. `bridge.timeout` in
  `skord.yml` sets how long a call waits (5 seconds by default). A call `without waiting` that fails
  is logged, not stored.
- Each side publishes its functions when it connects and after every reload. The bot saves each
  server's list in `.skord/servers/<name>.json`; once a list is known, a call to a name that isn't
  on it is a load error. Before that it loads, and fails at run time with `no such function`.
- The bot also offers `linked(discordId, uuid, name)` and `unlinked(discordId)`, which a server calls
  to keep the bot's Discord-to-player cache current. When the bot meets a Discord account it doesn't
  know, it asks the connected servers' `linkedPlayer(discordId)`, if they declare it, before routing
  a shared command.
- A server that holds the full list of links can also declare `linksPage(after: text) :: texts`.
  When it connects, the bot asks for pages until one comes back empty: each page is Discord ids and
  player uuids in turn (`id, uuid, id, uuid, ...`), starting after the id passed in (`""` for the
  first page). Once the last page arrives, the bot's cache is replaced with exactly what the server
  sent, so links removed while the bot was away go too. If a page fails, the old cache stays. A
  rebuild runs at most once a minute, whichever server triggers it.

### Embedded mode: the bot inside one server

A single server with no network can run the bot itself. Set `mode: embedded` in `bridge-minestom`'s
`config.yml` with the bot's `token:` (the `SKORD_TOKEN` environment variable overrides it),
`intents:` and an optional `dev-guild:`. Scripts sit next to the server's other scripts and use the
same syntax, and `sk reload` reloads them with command sync.

There is no bridge in embedded mode: shared functions, presence and `from "<server>"` are load
errors there. `held` is accepted and runs as a plain handler, so a script written for a linked
server still loads. Embedded mode is Minestom only for now.

### Java API

Servers that run no Skript build on three thin jars: `protocol`, `discord-api` and `bridge-api`.
`Bridge.start(config, platform)` connects. From there a plugin declares commands with handlers,
subscribes to events, declares and calls shared functions, sends Discord actions, reports players
joining and leaving, and listens for the connection going up or down. `BridgeConfig.withMeta` adds
metadata to the hello. On Paper, get the running
`Bridge` from the `ServicesManager`.

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

### `skord.yml`

| Key | Default | What it does |
|---|---|---|
| `token` | | the bot token; `SKORD_TOKEN` overrides it |
| `intents` | | which events Discord sends; the first start writes `guild-messages`, `direct-messages` and `message-content`; boosts also need `guild-members` |
| `commands.dev-guild` | | a guild id; commands register there and update instantly |
| `bridge.port` | off | the port game servers connect to |
| `bridge.bind` | `0.0.0.0` | the address to listen on |
| `bridge.timeout` | `5` | seconds a shared function call waits |
| `bridge.route-order` | | server names, in the order a shared command falls back to |
| `bridge.held.max-events` | `1000` | held events queued per server |
| `bridge.held.max-age` | `7d` | how long a held event stays queued |

The bot keeps its state in `.skord/`: link secrets (`links.yml`), each server's commands and
functions (`servers/`), held events (`held/`) and the player link cache (`player-links.json`).

## Console

| Command | Does |
|---|---|
| `sk reload all` | reload `config.sk` and every script |
| `sk reload <script>` | reload one script, or every script in a folder |
| `sk reload config` | reload `config.sk` |
| `sk enable <script>` / `sk disable <script>` | turn a script on or off |
| `sk list` | show loaded and disabled scripts |
| `sk link create <server> [--allow a,b] [--listen a,b]` | let a game server connect; prints its secret once |
| `sk link scope <server> [--allow a,b] [--listen a,b]` | change what a server may do, at once |
| `sk link revoke <server>` | cut a server off and forget its secret |
| `sk link list` | show linked servers, whether each is connected, and their scopes |
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

| Module | What it is |
|---|---|
| `core` | the standalone bot: `skord.jar` |
| `jda` | Discord through JDA, shared by the bot and embedded mode |
| `syntax` | the Discord syntax, loaded wherever Skript runs |
| `protocol` | bridge frames, the WebSocket client, scopes |
| `discord-api` | the platform-free Discord port and references |
| `bridge-api` | the Java API game servers code against |
| `bridge-minestom`, `bridge-paper` | the server-side platform jars |
| `embedded` | embedded mode |
| `testbot` | the fake Discord and the test scripts |

## License

GPL-3.0. See [LICENSE](LICENSE).
