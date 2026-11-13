<div align="center">

# MCP Remastered

**Interactive fetcher, decompiler and differential patcher for Minecraft 26.1 and later.**

![Language](https://img.shields.io/badge/language-JDK_25-CE422B?logo=openjdk&logoColor=white)
![Decompiler](https://img.shields.io/badge/decompiler-Vineflower-6E4A8E)
![Platforms](https://img.shields.io/badge/platforms-Windows%20%7C%20Linux%20%7C%20macOS-4B5563)
![Engineering Confidence: 7/10](https://img.shields.io/badge/Engineering%20Confidence-7%2F10-4CAF50)
![Architecture](https://img.shields.io/badge/architecture-hand%20designed-2E7D32)

</div>

---

## 📦 About

**MCP Remastered** is a command line toolchain that turns a Minecraft release into a project:
the readable Java sources of the game, the assets the game carries in its jar, and the runtime the
game is started from.

It speaks the protocol of the official launcher, so everything it downloads is exactly what
Mojang publishes, and it can start the game from the files it has put together.

The sources it produces are meant to be edited: they are the source root of a module of the
project, the IDE compiles them, and the game can be debugged right there. The tool keeps the
changes usable when the next release arrives:

```text
Start MCP Remastered
        ↓
Fetch the version manifest
        ↓
Keep the 26.1 era and later
        ↓
┌──────────────────────────┐
│ Stable release           │ → Download, decompile, patch
│ Snapshot                 │ → Download, decompile, patch
│ Version given on the CLI │ → Skip the questions
└──────────────────────────┘
        ↓
Decompile with Vineflower
        ↓
Put the sources in src/main/java
        ↓
Diff against the previous version
        ↓
Carry the changes over
```

## ⚡ Features

* ✅ **Interactive version picker**, with stable releases and snapshots kept apart
* ✅ **Only the 26.1 era and later**, the older versions are filtered out
* ✅ **Downloads verified by SHA-1**, for the jars, the libraries and every asset
* ✅ **A runtime that looks like a `.minecraft` folder**, with the assets content addressed
* ✅ **The sources are the project**, in `src/main/java`, ready to be edited in the IDE
* ✅ **The assets of the jar are the project resources**, in `src/main/resources`
* ✅ **The game can be debugged**, the IDE compiles the sources and runs the game
* ✅ **Decompilation with Vineflower 1.12**, with the libraries on the classpath
* ✅ **Differential patching**, at the bytecode level, at the source level and as a merge
* ✅ **Runs on JDK 25**, the runtime Minecraft 26.x asks for, picked from the Maven toolchains
* ✅ **Assets downloaded in parallel**, with the hash computed while the bytes stream past
* ✅ **Native libraries extracted**, the layout the game expects

## 🔄 The pipeline

MCP Remastered walks through fourteen steps, and every one of them can be looked at on its own.

| # | Step | What happens |
|---|------|--------------|
| 1 | Fetch manifest | `GET https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` |
| 2 | Deserialize | The manifest is parsed with GSON into `VersionManifest` |
| 3 | Filter | Only the `26.1+` versions are kept |
| 4 | Ask the channel | *stable* or *snapshot* |
| 5 | Ask the version | A numbered list, index `0` is the newest entry of the channel |
| 6 | Version JSON | The per-version JSON is fetched and deserialized into `VersionJson` |
| 7 | Jars and libraries | `client.jar`, `server.jar` and every library that applies to this OS |
| 8 | Asset index | The asset index is fetched, deserialized and stored where the game reads it |
| 9 | Assets | Every asset is downloaded from the CDN into the content addressed store |
| 10 | Internal assets | The assets the game carries inside its jar are taken out into `src/main/resources` |
| 11 | Bytecode patch | The classes of the client are patched with ASM, on the original jar |
| 12 | Decompile | The **patched** jar is decompiled with Vineflower into `src/main/java` |
| 13 | Source repairs | The differences of the version are applied to the sources |
| 14 | Differential patch | The new version is compared against the previously processed one |

Bytecode patches run before the decompiler on a separate jar when patches are registered. For
26.3, seven patches are registered. They all recognise a shape in the bytecode and apply
everywhere that shape occurs, so a single patch covers every class that carries it.

The patches live in `tool/src/main/java/mcp/remastered/engine/pipeline/patch/bytecode/fixes`. They
cover the generic conversions that the decompiler cannot rebuild: a superclass method that
returns its own type, a method of the class that does the same, the target of a lambda that the
compiler nested in another one, the local of a catch block that the compiler reused, a helper that
takes a `Class` and returns it, the two local variables of a method that share a name, and the
method reference that builds a comparator. Some patches change generic signatures or debug names;
others insert casts or private helper methods and therefore change executable instructions.
These transformations aim to preserve gameplay semantics, but compilation alone does not prove
equivalence. The intermediate patched JAR is unsigned: the original publisher's signature cannot
remain valid after its classes change.

What is specific to one class is not patched here. A type witness the compiler wrote and then
erased, a cast between two unrelated types and a local variable the decompiler did not declare
are things that only exist in the text of the source: the class file carries none of them. Those
are repaired in the sources, with a difference.

## 🔧 The repairs of the sources

The folder `fixes/<version>/` holds the differences that are applied to the sources the
decompiler writes. They are unified differences, the format `git diff` writes, and they are
applied after the decompilation and before the sources are written to the project, so that the
tree of the project and the baseline of the differential layer agree.

For 26.3 there is one file, `fixes/26.3/decompiler-fixes.diff`, and it repairs eighty-eight
classes. Failed repairs now stop preparation instead of publishing a partially repaired tree.

A difference that does not apply is not an error that passes unnoticed: the pipeline says which
file and which piece did not fit, because that means the decompiler wrote something else than it
did the last time, and that has to be looked at.

To write a repair, the sources of the project are fixed by hand and the difference is taken
from there:

```bash
git diff -- src/main/java > fixes/26.3/my-repair.diff
```

The difference is written against the sources the decompiler gives, so a change in the bytecode
patches or in the decompiler itself can make it stop applying. That is what the report is for.

The signatures carry the types the decompiler writes, and a wildcard in them is a type the
compiler cannot name. A member declared with an unbounded wildcard is the root of most of the
errors the sources end up with: the decompiler turns it into a cast the compiler rejects. Naming
the type the member really carries fixes them, one member at a time, but such a rewrite can also
break a class that used the member, so every one of them has to be measured on the whole tree
before it is kept. The ones that were kept are the general patches, and what is specific to a
class is left to the differences.

The two layers are complementary: a class can need both a general bytecode transformation and
a source repair. The source differences target the output of the configured ASM/Vineflower pipeline.

After downloading the libraries, the tool updates the managed `mcp-remastered-game-libraries`
Maven profile in `game/pom.xml` using the selected version's metadata. It points at the local
JARs under `runtime/libraries`; reload the Maven project in the IDE after setup. Maven may warn
about project-local `system` dependencies: they work in this workspace but cannot be resolved by
downstream projects without the downloaded files. Set `mcp.disableGameLibraries` to disable the
profile when building only the tool.

The identifiers of the newer versions are not all of the same shape, and the tool knows how to
read them: `26.3`, `26.1.2`, `26.4-snapshot-7`, `26.2-pre-6`, `26.1-rc-3` and the legacy
`26w14a`.

## 📥 Downloaded files

Mojang serves the assets as content addressed blobs: the index maps a resource path such as
`minecraft/font/alt.json` to the SHA-1 hash of the file. The tool keeps them the way the official
launcher does, in a folder that is a `.minecraft` folder in everything but the name.

```text
runtime/assets/
  objects/b6/b62ca8ec…        the content addressed store, exactly as the launcher keeps it
  indexes/34.json             the index the game reads
```

The game looks the assets up by the hash the index gives it, so nothing else has to be kept
around and the folder is a valid `.minecraft` folder as it is.

The files are written to a temporary `.part` file and renamed once the download is over and the
hash matches, so an interrupted run never leaves a half written file behind.

## 🔀 Differential patching

The point of the patching layer is that work done on one version should survive the next one. It
operates on three levels:

- **Bytecode level** (`ClassIndex` / `ClassDiff`, built on ASM): every class of the client jar is
  reduced to a fingerprint covering its super class, its interfaces, its fields and a hash of the
  bytecode of each of its methods. Comparing two versions yields the classes that were added,
  removed or modified, and for a modified class the exact set of methods whose body changed.
- **Source level** (`LineDiff` / `SourcePatcher`): a line based diff of the decompiled trees
  produces unified diffs and classifies every file as `UNCHANGED`, `PORTABLE`, `CONFLICT`,
  `ORPHANED` or `NEW`.
- **Merge level** (`SourceMerger`): the customisations are carried over automatically with a
  three way merge. A change made on only one side is kept, and when both sides touched the same
  lines the region is reported as a conflict instead of being guessed at.

The result is written to `patches/<old>__<new>/`, containing `sources.diff` and a `summary.txt`
that merges the first two levels.

The unified diff is a normal diff, so `git apply` and `patch` accept it as is:

```bash
cd src/main/java
git apply ../../patches/26.2__26.3/sources.diff
```

### Working with the sources

The decompiled sources live in `src/main/java`, the source root of the game module. They are
meant to be edited in the IDE or in any other editor. The tool has a separate source root.

The tree the project was derived from is kept in `cache/baseline/`. It is the reference the tool
uses to tell what the user changed, so it is never touched by hand. When a new version shows up,
the tool:

1. decompiles the new version aside, into `cache/prepared/`,
2. diffs `cache/baseline/` against `src/main/java` to learn which lines were touched,
3. merges those changes into the sources of the new version with a three way merge,
4. writes the result back to `src/main/java`, and
5. leaves a report of the files that could not be merged automatically in `patches/<old>__<new>/`.

The files that the new version brought in are added to the project, and the files that it removed
are left alone, so that nothing is lost.

Some files still come out of the decompiler with compilation errors, and most of them cannot be
fixed in the bytecode. A type witness the compiler wrote and then erased, a cast between two
unrelated types and a local variable the decompiler did not declare are things that only exist in
the text of the source: the class file carries none of them. Those are repaired by the
differences of the version, and the general bytecode patches take care of the rest.

For 26.3 the sources of the game compile without a single error: seven general patches and the
differences of `fixes/26.3/decompiler-fixes.diff`, which repair eighty-eight classes.

## 🎮 Running the game

The game sources are compiled by the IDE. The tool prepares:
the sources in `src/main/java`, the assets of the jar in `src/main/resources`, the natives
extracted in the layout the JVM arguments point at, the libraries in `runtime/libraries` and the
assets of the CDN in `runtime/assets`.

The configuration of the IDE is **not** touched by the tool. The root `pom.xml` is the
aggregator: it holds the tool in `tool/` and the game in `game/`. Import the root `pom.xml` and
IntelliJ picks up both modules. The game module depends on the tool, so the classes of the tool
are on the classpath of the game: the run configuration that starts the game uses the **game**
module, and the main class is the one of the tool.

A module of the game needs:

| | |
|---|---|
| source root | `src/main/java` |
| resources | `src/main/resources` |
| classpath | the game module's generated Maven dependencies and compiled game classes |

A run configuration of the game needs:

| | |
|---|---|
| main class | `net.minecraft.client.main.Main` |
| working directory | `runtime/game` |
| JVM arguments | `-Djava.library.path=runtime/natives/<id>/java`, `-Dorg.lwjgl.system.SharedLibraryExtractPath=runtime/natives/<id>/lwjgl`, `-Dio.netty.native.workdir=runtime/natives/<id>/netty` |
| program arguments | `--username <name> --version <id> --gameDir runtime/game --assetsDir runtime/assets --assetIndex <n> --offlineDeveloperMode` |

The tool runs in **offline developer mode**, so no session of Mojang is needed and the player gets
an identifier derived from the name. The options are:

| Option | Default | What it does |
|--------|---------|--------------|
| `--version <id>` | asked | The version to use, skips the questions |
| `--overwrite-sources` | off | Re-decompile and overwrite matching game Java files for the current baseline version |
| `--launch` | off | Run the already prepared game on the current IDE classpath, in offline developer mode |
| `--username <name>` | required for launch | Player name |
| `--uuid <uuid>` | required for launch | Player UUID, with or without hyphens |
| `--token <token>` | required for launch | Access token; `0` is accepted for offline development |
| `--workspace <path>` | the current folder | Where everything is written |
| `--game-dir <path>` | `<workspace>/runtime/game` | The folder the game writes to |

`--overwrite-sources` always decompiles again and replaces matching Java files, including your
edits to those files; unrelated files and sources no longer present in the new output are kept.
It refreshes the baseline and is limited to the existing baseline version. Without the flag,
existing edits remain protected by the normal merge flow.
Previously downloaded client/server JARs, game libraries, the asset index and asset objects are
reused after checking their size and SHA-1 where supplied; missing or corrupt downloads are fetched
again. Already extracted native files are kept when they match their archive entries. Internal
resources previously extracted for the same version are not overwritten.

### From IntelliJ

The tool is a plain Java program, so a run configuration of type *Application* is enough to run
it: the main class is `mcp.remastered.engine.Main` and the arguments are the ones of the table
above, for instance `--version 26.3`.

To debug the game, compile its sources and run `mcp.remastered.engine.Main` from the IDE with
`--launch --version 26.3 --username Player --uuid 00000000-0000-0000-0000-000000000000 --token 0`. This invokes the game main in the same JVM and does
not run setup or load the original client jar. The IDE run configuration must include the game
classes and libraries and provide any JVM startup flags required by the selected version; JVM
startup flags cannot be added after the JVM has started. In particular, configure native access
and native library paths as appropriate for your platform.

> [!IMPORTANT]
> The game needs a Java 25 to run, and so does the tool.
>
> The project JDK of IntelliJ has to be a JDK 25 or newer, and the tool picks its JDK from the
> Maven toolchains when it builds.

## 📁 The project

The tool works on the project it lives in, so that what comes out of it is a project the user
can work on right away: the sources of the game in `src/main/java`, the assets the game carries
inside its jar in `src/main/resources`, and a runtime that is a `.minecraft` folder.

```text
MCP Remastered/
  pom.xml                         the aggregator, it holds the two modules
  tool/
    pom.xml                       the tool
    src/main/java/
      mcp/remastered/engine/…      the code of the setup tool
  game/
    pom.xml                       the game module and its downloaded libraries
  src/main/java/
    net/minecraft/…            the sources of the game, the files the user edits
    com/mojang/…               the sources of the game, the files the user edits
  src/main/resources/           the assets the game carries inside its jar
  fixes/<version>/              the differences applied to the sources of the game
  runtime/                      the .minecraft folder
    assets/
      objects/<xx>/<hash>         the assets, content addressed
      indexes/<id>.json           the index the game reads
    libraries/<maven path>        the libraries of the game
    natives/<id>/…                the native libraries
    versions/<id>/
      jars/client.jar, server.jar
      metadata.json
    game/…                        the folder the game writes to
  cache/
    baseline/                     the sources the project tree was derived from
    baseline-version.txt          the version the baseline belongs to
  patches/<old>__<new>/
    sources.diff
    summary.txt
```

The root Maven project is the aggregator of the two: `tool/` builds the tool, `game/` builds the
game from `src/main/java`. The tool does not export a game jar.

From the command line the tool is run on the game module, which has the classes of the tool and
the libraries of the game on its classpath:

```bash
mvn -pl tool -am package                           # build the tool before downloading game libraries
mvn -pl tool validate exec:exec "-Dexec.args=--version 26.3"
mvn install                                       # compile the prepared game and install both modules
mvn -o -pl game validate exec:exec "-Dexec.args=--launch --version 26.3 --username <name> --uuid <uuid> --token <token>"
```

The libraries of the game have the `system` scope, which Maven keeps out of the runtime classpath,
so the goal is told to use the scope of the test classpath, where they are all.

Run Maven itself with Java 25 (`JAVA_HOME` and `PATH`), not Java 8. The compiler toolchain does not
necessarily select the JVM used by every plugin or launched process. Omit `-o` on the first build
so Maven can obtain its dependencies; setup also needs network access to Mojang.

### Generated files and clean workspaces

The checkout does not include game sources, game resources or downloaded libraries. Setup creates
`src/main/java/net`, `src/main/java/com`, `src/main/resources`, `runtime/` and `cache/`, and updates
the managed library profile in `game/pom.xml`. Compile the game only after setup succeeds, then
reload Maven in IntelliJ. `--overwrite-sources` is for deliberately replacing existing generated
sources, not a requirement for the first setup.

Build outputs (`target/`, `tool/target/`, `game/target/`), extracted sources/resources, `cache/`,
`patches/` and downloaded runtime files can be regenerated. Back up source edits before removing
generated sources. **Deleting `runtime/game` also deletes saves, screenshots and player settings**;
do that only when intentionally resetting local game data. Keep `fixes/`, tool sources and project
configuration: they are inputs, not disposable output.

### Validation performed for 26.3

The repair review verified application of all 88 file repairs and compilation of all 7,301
decompiled Java files (zero errors; warnings remain). The regenerated intermediate ASM JAR passed
JVM loading/verification for all 885 changed classes without bypassing JAR signature checks.
Targeted differential checks were also performed on selected lambdas and comparators.

Source repair parsing respects hunk lengths; failed repairs stop setup. Repairs restore the finite
world-generation buffer cleanup loop and Vulkan feature-set defensive copies. The game-rule packet
codec keeps the original map getter instead of introducing an encoding-time copy. Alternative lambda
metafactory protocols are excluded from the helper-extraction patch pending separate review.

These checks and a successful local game launch do not prove complete gameplay equivalence or
absence of other decompiler defects.

## 🎮 Debugging

The tool prepares the project, the IDE does the rest: the sources of the game are in
`src/main/java`, the assets of the jar are in `src/main/resources`, the natives are extracted in
`runtime/natives/<id>` and the libraries are in `runtime/libraries`. A module and a run
configuration, set up once as described in [Running the game](#%EF%B8%8F-running-the-game), are
enough to start the game with the debugger attached.

Breakpoints in the sources of the game bind to the code that is running, because the sources are
what the module compiles.

## 🧠 Engineering confidence

**7/10 — Code written entirely with AI assistance, architecture and implementation choices made entirely by hand**

The code of MCP Remastered was written entirely with the assistance of an AI, but the architecture
and the implementation choices were made entirely by hand.

Every decision that shaped the tool was taken deliberately, against the real services of Mojang
and the real format of the game:

* the pipeline was laid out to follow the twelve steps of the official launcher, so that every
  stage maps on something Mojang actually publishes;
* the three levels of the differential patching were picked because each one answers a question
  the others cannot: what changed in the code, what changed in the sources, and what can be
  carried over;
* the decision to keep the runtime a plain `.minecraft` folder, with the assets content
  addressed and nothing else, came from reading how the game resolves its own assets;
* the decision to keep the decompiled sources in `src/main/java`, separate from the code of the tool,
  came from the way the game is meant to be worked on: the sources are read, changed, compiled
  and debugged, so they have to be part of the project;
* the decision to leave the sources of the game out of the build of the tool came from the same
  place: the game is built by the IDE;
* the decision to leave the configuration of the IDE alone came from the same place too: it
  belongs to the person who works on the project, and it is set up once.

The pipeline runs from the manifest down to the decompiled sources. Static comparisons,
compilation checks and targeted runtime checks help identify regressions; their coverage and
limitations matter more than a successful build alone. See the 26.3 validation summary above.

Game behavior can be tested, but the checks performed here do not exhaust all seeds, dimensions,
entities, rendering backends, multiplayer scenarios or supported platforms.

It does not mean that the tool is known to be unreliable; it means that the project does not claim
complete absence of bugs or platform specific malfunctions.
## ⚠️ Reliability and limitations

MCP Remastered is designed to fail gracefully where possible, but no toolchain can guarantee
compatibility with every environment.

Potential issues can include:

* interrupted or corrupted downloads;
* the CDN refusing too many connections at once;
* filesystem permission problems;
* antivirus or security software interference;
* a Java runtime older than the one the game asks for;
* the game refusing to start because of a missing session of Mojang;
* unusual home directory configurations;
* changes in the format of the manifest or of the version JSON.

The download of the assets retries and verifies every hash, so a corrupted file is reported rather
than used.

> [!IMPORTANT]
> The sources are the output of a decompiler, not the original code of Mojang.
>
> They are meant to be read and patched, not to be redistributed as if they were the source of
> the game.

## 🔐 Privileges

The tool itself does not normally need to run permanently as administrator or root.

It writes inside the project it works on: the sources of the game in `src/main/java`, the assets
in `src/main/resources`, the runtime in `runtime/` and the baseline
in `cache/`. It reads the files of the Java installation it is started with.

## 🛠️ Built with

MCP Remastered uses Java together with:

* **GSON** — the JSON of the manifest, of the versions and of the asset index;
* **ASM** — reading the Java 25 class files and the bytecode level differential analysis;
* **Vineflower** — the decompiler that turns the client jar back into Java sources;
* **JUnit 5** — the tests of the parts where being subtly wrong is expensive.

The JDK used to build comes from the Maven toolchains, so the build does not depend on the
`JAVA_HOME` of the machine.

```xml
<toolchain>
  <type>jdk</type>
  <provides>
    <version>25</version>
  </provides>
  <configuration>
    <jdkHome>C:\path\to\jdk-25</jdkHome>
  </configuration>
</toolchain>
```

## 🧪 Project status

The tool has been tested extensively for its intended workflow, but should still be treated as
software that can encounter unforeseen system specific bugs.

If a run fails unexpectedly, keep a copy of the important local data and report the environment,
the operating system and the error shown by the tool.

## ⚖️ Warranty

> [!CAUTION]
> This software is provided **as-is**, without warranty of any kind.
>
> Although the tool has been extensively tested, complete absence of bugs, installation failures
> or platform specific problems is not guaranteed.
