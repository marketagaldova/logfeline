# Logfeline #
A better adb logcat, written in kotlin.

## Features ##
- Filter by app without fuss, simply select the one you want from a list.
- Filter by tag or priority.
- Convenient device selection. No need to bother with serial numbers, ip
  addresses, or whatever else.
- Auto reconnects. Logfeline can tell if a device is connected via multiple
  options (for example both usb and tcp), and switch between them as necessary.
  If a device gets disconnected completely, it will reconnect when it becomes
  available again.
- Screenshots? Apparently so.
- Pretty colors ✨

## Requirements ##
Java 17 is required at minimum.

The adb client itself only requires a fairly modern adb server to be running on
`localhost`. It will respect `ANDROID_ADB_SERVER_PORT` or default to `5037` when
that isn't set. Alternatively, you can specify the host and port on the command
line using the `server=<host>:<port>` option (`server=:<port>` is also valid and
will use `localhost` for the hostname).

You can start an adb server with `adb start-server`. The version I tested with is
`1.0.41` (packaged in platform tools `35.0.0`). Anything newer should be fine.
Older versions might work, but no guarantees on that. You can check your adb
version with `adb --version`.  
*TODO: Attempt to start the server automatically*

The client interface is really only built for linux though. It requires a
terminal with support for ANSI escape sequences and `stty` to be available on the
`PATH`. You may have some luck on mac or in wsl on windows, but linux is the only
properly supported platform at the moment. I do plan to make a gui version in the
future as well, which should be more cross-platform, but that does not exist yet...

## Build ##
Java 17 is required for the build to work.

You can either run `./gradlew installDist`, which will create launch scripts in
`./client/build/install/client/bin`, or `./gradlew build`, which will create a
standalone executable jar in `./client/build/libs/logfeline-<version>.jar` and
a shebangified version of this same jar in `./client/build/bin/logfeline`. The
shebangified jar can be used like a normal executable without the need to call
`java -jar` every time, but otherwise it is identical to the regular one.

## Short guide ##
When selecting a device or app from the menu, you can either use the up and down
arrows to make your selection, or you can start typing to search. When selecting
an app, only debuggable apps are shown by default, but other apps will show up
when searching. Search looks through the app's package id, as well as the label
when available. Note that search results will only update when the query changes.

Once in the log view, there are several options:
- Press `q` to exit.
- Press `/` to set a filter. This will show a prompt where you can type a
  space-separated list of filters. Press enter to go back. The filter syntax is
  as follows:
  - `tag:<text>` or `-tag:<text>` - Include or exclude tags matching the given
    `<text>` exactly.
  - `tag.contains:<text>` or `-tag.contains:<text>` - Same as `tag:` and `-tag:`,
    but matches any tags that contain `<text>` instead of a full match.
  - `priority:<priorities>` or `-priority:<priorities>` - Include or exclude the
    given list of priorities. `<priorities>` is a comma-separated list of words
    indicating the priorities. Valid values are `VERBOSE`, `DEBUG`, `INFO`,
    `WARN`, `ERROR` and `FATAL`, but any case-insensitive prefix of those will
    work as well, for example `-priority:v,d` will filter out `VERBOSE` and
    `DEBUG` messages.
- Press `:` to run a command. This will show a prompt where you can type the
  command. Press enter to confirm. To cancel simply leave the prompt blank before
  pressing enter. Currently supported commands are:
  - `save filter` - This will save the current filter for this device/app
    combination. Next time you start logfeline, the filter will be applied by
    default to this device/app combination.
  - `highlight <style>` - Changes the tag highlighting style. `<style>` can be one
    of:
    - `none` - No color highlighting.
    - `basic` - Only change text color.
    - `dim` (the default) - Bright text on dim background.
    - `bright` - Dim text on bright background.
  - `screenshot <path>` or `ss <path>` - This will take a screenshot of the device. `<path>` is the
    directory in which the screenshot will be stored, though it can be omitted, in
    which case `~/logfeline/screenshots` will be used.

## Some technical bits ##
- Logfeline does not use the `adb` client, it connects to the server directly. This
  allows for more flexibility and speed. It also means that the `adb` module is
  basically a generic adb client library, so I may publish it as such at some point.

- The way logfeline obtains app labels is a rather ugly hack, but one that works
  surprisingly well. Since there is no good way to get app labels via adb or even
  the device shell, what it does instead is push a dex with a small java program
  to the device, which then acts as a server from which we can request the labels.
  When it receives a request for a label, it looks it up using the android sdk and
  sends it right back. Note that this is all done via the adb connection - there is
  no actual tcp or other server being run on the device. This is still not the
  fastest operation though, hence why you will see `Waiting for labels...` anyway.

- If you for whatever reason want to do something similar to this, then
  [the adb source](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/services.md)
  is your friend. The linked file in particular is very useful, although it is quite
  incomplete, so you will have to hunt around for information in the actual source.

- Working with java io this much is pain. Would not recommend. In fact this is probably
  the only thing that kotlin makes worse as forcing exception checks actually makes
  a lot of sense and it becomes really easy to miss when you're not forced by the
  compiler. Still, writing all the rest in java would be much worse, so here we are.
