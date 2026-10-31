-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ViewModels and Workers are resolved by name at runtime.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# kotlinx.serialization generated serializers.
-keepclassmembers class com.shinsaikou.** {
    *** Companion;
}
-keepclasseswithmembers class com.shinsaikou.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    *** descriptor;
}

# Two things the release build gets wrong without these rules, both of which stop the application
# before it draws anything and neither of which the debug build can show.
#
# `Injekt.addSingleton<T>` is reified: it compiles to an anonymous `FullTypeReference<T>` in the
# caller, whose only record of T is its generic superclass, read back with reflection. R8 merges
# that anonymous class away, and the constructor throws
# `IllegalArgumentException: Internal error: TypeReference constructed without actual type
# information` on the application scope, which is a fatal exception on a coroutine dispatcher and
# kills the process a second after launch. Keeping the subclasses stops the merge; `Signature` is
# already kept above. The rule matches the anonymous classes, not Injekt's own, so
# `extensions/consumer-rules.pro` keeping `uy.kohesive.injekt.**` does not cover it.
-keep class * extends uy.kohesive.injekt.api.TypeReference

# Compose navigation resolves a type-safe route by its serial name, which defaults to the class's
# fully qualified name, through `Class.forName`. Obfuscating the route classes leaves
# `Cannot find class with name "com.shinsaikou.navigation.BrowsePage"` at the first navigation. The
# serializers are already kept by the kotlinx.serialization rules above; this keeps the names.
-keepnames class com.shinsaikou.navigation.**

# ---------------------------------------------------------------------------------------------
# Kept so the release build can be instrumented.
#
# AGP minifies the androidTest APK with the application's own mapping and leaves out anything the
# tested variant already carries. The consequence is that a member R8 removed because nothing
# shipped calls it is removed for the test as well, and the call fails at run time with
# NoSuchFieldError or NoSuchMethodError rather than at build time. Phase 0 seeds its benchmark
# content and opens the reader from instrumentation running against the release APKs, so the few
# members it reaches have to survive shrinking (carry-over 13).
#
# Every rule below names an exact member that `mapping/release/usage.txt` listed as removed, and
# every one of them belongs to a type the application already keeps alive. None of them pulls in a
# subgraph the release build did not already contain.

# The application resolves its dependencies by injection and never asks the graph for these three
# by name. They are how BenchFixtureSeeder enters the graph.
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    com.shinsaikou.sources.local.LocalSource getLocalSource();
    tachiyomi.domain.manga.repository.MangaRepository getMangaRepository();
    tachiyomi.domain.chapter.repository.ChapterRepository getChapterRepository();
}

# `AndroidJUnitRunner.onCreate` calls `androidx.tracing.Trace` and nothing the application ships
# does, so R8 removes the class and every instrumentation run against the release build dies before
# the first test with `NoClassDefFoundError: Landroidx/tracing/Trace;`. That is the run that exists
# to catch this class of damage. Two small classes, kept so the run can happen.
-keep class androidx.tracing.** { *; }

# The reader's view model is reached through `ViewModelProvider` so the hold reads the instance the
# activity already has rather than building a second reader session. Compose obtains view models
# through the Metro factory, so R8 inlines every route to this class and then removes it, and the
# hold dies with `ClassNotFoundException: androidx.lifecycle.ViewModelProvider`.
-keep class androidx.lifecycle.ViewModelProvider { *; }
-keep class androidx.lifecycle.ViewModelProvider$* { *; }

# The shipped application only ever reads a reading mode back out of a stored flag, so R8 drops the
# constants no code names literally: LEFT_TO_RIGHT and VERTICAL. BenchFixtureSeeder names
# LEFT_TO_RIGHT to write the paged entry's mode, and its class initialiser failed with
# `NoSuchFieldError: No field LEFT_TO_RIGHT` until this rule.
-keepclassmembers enum com.shinsaikou.domain.manga.model.ReadingMode {
    *;
}

# Every member of this object is removed: the reader is opened from one call site, which R8 inlines,
# leaving nothing behind to call. The benchmark builds the same intent from instrumentation rather
# than duplicating the extra names, which is the point of the object existing.
-keep class com.shinsaikou.reader.ReaderIntents {
    *;
}

# R8 does not only delete: it rewrites. A property whose accessor no shipped call site uses is read
# as a field instead and the accessor goes; a suspend function whose every caller passes the same
# continuation subclass has its parameter type narrowed to that subclass. Both leave a method the
# instrumentation cannot call, one with NoSuchMethodError on a name and one on a signature. The
# members below are the ones `BenchFixtureSeeder` and `BenchContentTest` reach, and each is named
# because the release APK was checked against the release androidTest APK reference by reference.

# Read off the reader's published state.
-keepclassmembers class com.shinsaikou.reader.ReaderViewModel {
    kotlinx.coroutines.flow.StateFlow getState();
}
-keepclassmembers class com.shinsaikou.reader.state.ReaderState {
    com.shinsaikou.reader.state.ReaderPhase getPhase();
    com.shinsaikou.domain.manga.model.ReadingMode getReadingMode();
    int getTotalPages();
}

# Listing the generated directory through the local source, and turning what it returns into rows.
-keepclassmembers class com.shinsaikou.sources.local.LocalSource {
    java.lang.Object getSearchManga(int, java.lang.String, com.shinsaikou.source.model.FilterList, kotlin.coroutines.Continuation);
    java.lang.Object getChapterList(com.shinsaikou.source.model.MangaInfo, kotlin.coroutines.Continuation);
}
-keepclassmembers class com.shinsaikou.source.model.MangaPage {
    java.util.List getMangas();
}
-keepclassmembers class com.shinsaikou.source.model.MangaInfo {
    java.lang.String getTitle();
}
-keepclassmembers class com.shinsaikou.source.model.ChapterInfo {
    java.lang.String getUrl();
}
-keep class com.shinsaikou.source.model.FilterList$Companion {
    com.shinsaikou.source.model.FilterList getEMPTY();
}
-keep class com.shinsaikou.domain.source.SourceModelConversionsKt {
    tachiyomi.domain.manga.model.Manga toDomainManga(com.shinsaikou.source.model.MangaInfo, long);
    tachiyomi.domain.chapter.model.Chapter toDomainChapter(com.shinsaikou.source.model.ChapterInfo, long);
}

# Writing the reading mode onto the entry. The whole file goes otherwise: the application reads a
# mode out of the flags and never writes one, because the only writer is the reader's own settings
# sheet and that calls an interactor which R8 inlines.
-keep class com.shinsaikou.domain.manga.model.ViewerFlagsKt {
    long withReadingMode(long, com.shinsaikou.domain.manga.model.ReadingMode);
}

# The Compose compiler emits a `$stable` constant on every class it sees and a read of it wherever
# one is referenced as a type. Nothing the application ships reads SaikouApp's, so R8 drops the field
# and `ExtensionHostTest`, which casts the application context to it, fails in its class initialiser
# with `NoSuchFieldError: No field $stable of type I in class Lcom/shinsaikou/SaikouApp;`.
-keepclassmembers class com.shinsaikou.SaikouApp {
    public static final int $stable;
}

# ---------------------------------------------------------------------------------------------
# Kept so the release build can be instrumented: the reference lib-1.6 run, phase 0 step 7.
#
# Generated by tools/r8/check_test_references.py. Regenerate with:
#
#   python3 tools/r8/check_test_references.py \
#       app/build/outputs/apk/release/app-release.apk \
#       app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
#       --test-class com.shinsaikou.extensions.ExtensionHostTest --keeps
#
# Step 7 runs ExtensionHostTest against the release APK, which is the reference run of contract 03
# section 15 and the only thing in the gate that drives the shipped, shrunk application through a
# real extension end to end: register the catalog, refresh the index, install, check the signer,
# load, make four ABI calls, read the cookie store, uninstall. That path reaches members no shipped
# call site reaches, which is exactly the set R8 removes, and every one of them fails at run time
# rather than at build time. Each rule below names a class or a member the run reaches and R8
# removed or rewrote, found by resolving the release androidTest APK's references against the
# release APK reference by reference, which is how the benchmark keeps above were found as well.
#
# Two kinds of damage are represented here and they read differently. A member marked below as
# removed is simply gone, and the call fails with NoSuchMethodError or NoSuchFieldError on the
# name. A member marked as rewritten is still in the dex under a narrowed signature, because R8
# narrows a suspend function's continuation parameter to the one subclass every shipped caller
# passes; the call then fails on the signature rather than on the name, and the rule works by
# naming the original signature so the narrowing cannot happen.
#
# One member below is not in the checker's absent list. `ImageResult.bytes` comes back UNVERIFIABLE
# instead, because ImageResult implements java.io.Closeable and the resolution walk cannot
# enumerate a platform interface's members, so a class inheriting from one cannot be decided from
# two APKs. It was checked by hand against the release dex and the release mapping, which agree
# that R8 deleted it. This is the blind spot the checker's module docstring describes and it is why
# the UNVERIFIABLE list is worth reading rather than skimming.
#
# Two method names below carry a hyphen and a hash, `await-gIAlu-s` and
# `refreshAvailable-IoAF18A`. That is Kotlin's mangling for a function that returns a `Result`, and
# the mangled name is the real name in the dex, so a rule spelling the source name would match
# nothing.

# The graph entry points the run needs, beside the three the benchmark block keeps above. Removed,
# all four: everything inside the application is injected and nothing asks the graph for these by
# name, so from R8's side the accessors have no callers. They are the only way into the extension
# subsystem from instrumentation.
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    com.shinsaikou.extensions.store.AddExtensionStore getAddExtensionStore();
    com.shinsaikou.extensions.ExtensionManager getExtensionManager();
    com.shinsaikou.domain.extension.repository.ExtensionStoreRepository getExtensionStoreRepository();
    eu.kanade.tachiyomi.network.NetworkHelper getNetworkHelper();
}

# Steps 1 and 2, registering the catalog address and reading the store table back. `await` is
# rewritten rather than removed: every shipped call site passes the same continuation subclass, so
# R8 narrowed the parameter to that subclass and the method now exists under a signature the test
# cannot name. `getAll` is removed outright.
-keepclassmembers class com.shinsaikou.extensions.store.AddExtensionStore {
    java.lang.Object await-gIAlu-s(java.lang.String, kotlin.coroutines.Continuation);
}
-keepclassmembers interface com.shinsaikou.domain.extension.repository.ExtensionStoreRepository {
    java.lang.Object getAll(kotlin.coroutines.Continuation);
}

# Steps 3, 4a and 7, the manager's published state and its two operations. The five flows are
# properties the shipped application only ever collects from Compose, which reads the backing field
# directly, so R8 drops the accessors and leaves the fields; the instrumentation has no way to read
# a field that no longer has a getter. `uninstall` is rewritten, `install` and `refreshAvailable`
# are removed.
-keepclassmembers class com.shinsaikou.extensions.ExtensionManager {
    kotlinx.coroutines.flow.StateFlow getAvailableExtensions();
    kotlinx.coroutines.flow.StateFlow getInstalledExtensions();
    kotlinx.coroutines.flow.StateFlow getInstallSteps();
    kotlinx.coroutines.flow.StateFlow getLoadErrors();
    kotlinx.coroutines.flow.StateFlow getUntrustedExtensions();
    kotlinx.coroutines.Job install(com.shinsaikou.extensions.model.Extension$Available);
    java.lang.Object refreshAvailable-IoAF18A(kotlin.coroutines.Continuation);
    java.lang.Object uninstall(java.lang.String, kotlin.coroutines.Continuation);
}

# What an index entry and an installed extension say about themselves, steps 3 and 4a to 4c. Every
# one of these is a property the extensions screen renders through Compose, so the accessor has no
# call site the shrinker can see and the field is read directly instead. The assertions that the
# loaded lib version matches the announced one, and that the loaded source ids match the announced
# ids, are the ones that need both halves of the pair.
-keepclassmembers class com.shinsaikou.extensions.model.Extension$Available {
    java.lang.String getPackageName();
    java.lang.String getJarUrl();
    double getLibVersion();
    java.util.List getSources();
    tachiyomi.domain.extension.model.ExtensionStore getStore();
}
-keepclassmembers class com.shinsaikou.extensions.model.Extension$Installed {
    java.lang.String getPackageName();
    double getLibVersion();
    java.util.List getSources();
    java.io.File getExtFile();
}
-keepclassmembers class com.shinsaikou.extensions.model.AvailableSource {
    long getId();
}

# Step 4b, the signer check. The whole object is gone, not just a member of it: the shipped
# application verifies a signature from one call site inside the installer, R8 inlines it, and
# nothing is left to call. A whole-class keep rather than a member keep for that reason. The test
# reads the signer back out of the file that was installed, which is the assertion a swapped file
# has to fail, so it cannot borrow the installer's own result.
-keep class com.shinsaikou.extensions.loader.ExtensionSignatures {
    *;
}

# Step 4c, reading the package archive the installer left on disk. The object and its constant both
# go: the application's only reader of PACKAGE_FLAGS is inlined, and an object whose every member
# is inlined away loses its INSTANCE field too, so the class initialiser fails before the constant
# is ever read.
-keepclassmembers class com.shinsaikou.extensions.loader.ExtensionMetadata {
    com.shinsaikou.extensions.loader.ExtensionMetadata INSTANCE;
    int getPACKAGE_FLAGS();
}

# Steps 4d to 4g, the four ABI calls the run makes into the loaded extension. All four are
# rewritten rather than removed. Each is a suspend function, each has exactly one shipped caller,
# and each caller passes its own continuation subclass, so R8 narrowed all four parameters and left
# four methods the instrumentation cannot name. These are the calls the whole run exists to make,
# so a narrowed signature here is the difference between a reference run and a crash in step 4d.
-keepclassmembers interface com.shinsaikou.source.CatalogueSource {
    java.lang.Object getPopularManga(int, kotlin.coroutines.Continuation);
}
-keepclassmembers interface com.shinsaikou.source.Source {
    java.lang.Object getMangaUpdate(com.shinsaikou.source.model.MangaInfo, boolean, boolean, kotlin.coroutines.Continuation);
    java.lang.Object getPageList(com.shinsaikou.source.model.ChapterInfo, kotlin.coroutines.Continuation);
    java.lang.Object getImage(com.shinsaikou.source.model.PageInfo, kotlin.coroutines.Continuation);
}

# What those four calls hand back, and what the assertions read off it. All removed, and all for
# the same reason as the extension models above: the reader and the browse screen consume these
# through Compose, which reads the field. `ImageResult.bytes` is the member the checker can only
# call UNVERIFIABLE, explained at the top of this block; without it step 4g fails on the one call
# that proves the extension served an image at all.
-keepclassmembers class com.shinsaikou.source.model.MangaPage {
    boolean getHasNextPage();
}
-keepclassmembers class com.shinsaikou.source.model.MangaInfo {
    java.lang.String getUrl();
    boolean getInitialized();
}
-keepclassmembers class com.shinsaikou.source.model.MangaUpdate {
    com.shinsaikou.source.model.MangaInfo getManga();
    java.util.List getChapters();
}
-keepclassmembers class com.shinsaikou.source.model.PageInfo {
    int getIndex();
    kotlinx.coroutines.flow.StateFlow getProgress();
}
-keepclassmembers class com.shinsaikou.source.model.ImageResult {
    byte[] bytes();
}

# The logging the run does between steps. `logcat { }` is an inline function, so its body lands in
# the caller and the caller is instrumentation code that R8 never saw; the three things that body
# reaches are dead in the application because every shipped call site was inlined into code the
# shrinker could then simplify. The observer interface and the internal helper are gone as classes,
# not as members, which is why they are whole-class keeps. Without these the first logged line
# throws and the run reports a failure that has nothing to do with extensions.
#
# `isInstalled()` is in this list for a reason the checker could not see, and the reason is worth
# keeping. R8 does not only remove and rename: a method that never reads `this` is made static. The
# member survives shrinking under its own name in the app, so a resolver that matches on name and
# descriptor finds it and reports nothing, while the `logcat { }` body inlined into the test still
# carries an `invoke-virtual` to it. The device answers that with
# `IncompatibleClassChangeError: the method was expected to be of type virtual but instead was
# found to be of type static`, which is a fifth rewrite beyond the four carry-over 13 lists.
# Keeping the member is what stops the staticization: the two accessors below kept both their names
# and their invoke kind, and this one was renamed to `a` and made static because nothing named it.
-keepclassmembers class logcat.LogcatLogger$Companion {
    boolean isInstalled();
    java.util.List getLoggers();
    logcat.LogcatObserver getObserver();
}
-keep class logcat.LogcatObserver {
    *;
}
-keep class logcat.internal.Utils_jvmCommonKt {
    *;
}

# ---------------------------------------------------------------------------------------------
# Kept so the release build can be instrumented: the cold start pass with extensions, phase 0
# step 8 on the 2 GB AVD.
#
# Generated by tools/r8/check_test_references.py. Regenerate with:
#
#   python3 tools/r8/check_test_references.py \
#       app/build/outputs/apk/release/app-release.apk \
#       app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
#       --test-class com.shinsaikou.bench.BenchExtensionsTest --test-class com.shinsaikou.util.Fixtures \
#       --test-class com.shinsaikou.util.Args --keeps
#
# BenchExtensionsTest installs the first 76 anonymized sample packages through the application's
# private install, loads and trusts them through the extension manager, and removes them again.
# Step 8 runs it against the release APK, so every member below is one that path reaches and R8
# removed or rewrote, found reference by reference the same way as the blocks above. The rules
# the ExtensionHostTest block already carries (the manager accessor, its installed and untrusted
# flows, `uninstall`, `Extension$Installed.getPackageName`) are not repeated.

# The graph entry points the seed needs beyond the ones kept above: the trust preference, and the
# Injekt bootstrap the loader it builds is constructed with.
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    com.shinsaikou.domain.preferences.ExtensionsPreferences getExtensionsPreferences();
    com.shinsaikou.extensions.di.InjektBootstrap getInjektBootstrap();
}
-keepclassmembers class com.shinsaikou.domain.preferences.ExtensionsPreferences {
    tachiyomi.core.common.preference.Preference getTrustedExtensions();
}

# The manager's own operations for a package that is already on disk: load it, trust it, and wait
# for the first pass. `reload` and `trust` are suspend functions the shipped application calls
# from one place each, so R8 inlines or narrows them; `isInitialized` is a flow nothing shipped
# reads through its accessor.
-keepclassmembers class com.shinsaikou.extensions.ExtensionManager {
    java.lang.Object reload(java.lang.String, kotlin.coroutines.Continuation);
    java.lang.Object trust(com.shinsaikou.extensions.model.Extension$Untrusted, kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.StateFlow isInitialized();
}

# The private install itself. The application only ever reaches the loader through injection, so
# the constructor with default arguments the test calls is gone, and the two operations are
# reached from one call site each inside the installer.
-keepclassmembers class com.shinsaikou.extensions.loader.ExtensionLoader {
    <init>(android.content.Context, com.shinsaikou.extensions.trust.TrustStore, com.shinsaikou.extensions.loader.LibVersionPolicy, com.shinsaikou.extensions.di.InjektBootstrap, kotlinx.coroutines.CoroutineDispatcher, int, kotlin.jvm.internal.DefaultConstructorMarker);
    java.lang.Object installPrivateExtensionFile(java.io.File, kotlin.coroutines.Continuation);
    java.lang.Object readArchive(java.io.File, kotlin.coroutines.Continuation);
}
-keepclassmembers class com.shinsaikou.extensions.trust.TrustStore {
    <init>(com.shinsaikou.domain.extension.repository.ExtensionStoreRepository, com.shinsaikou.domain.preferences.ExtensionsPreferences, kotlinx.coroutines.CoroutineDispatcher, int, kotlin.jvm.internal.DefaultConstructorMarker);
}

# What the seed reads off an archive, an install outcome, a load result and the two extension
# models. Properties whose shipped readers all read the backing field.
-keepclassmembers class com.shinsaikou.extensions.loader.ExtensionArchive {
    java.io.File getFile();
    java.lang.String getPackageName();
    long getVersionCode();
}
-keepclassmembers class com.shinsaikou.extensions.loader.InstallOutcome$Rejected {
    com.shinsaikou.extensions.loader.InstallRejection getReason();
}
-keepclassmembers class com.shinsaikou.extensions.loader.LoadResult$Untrusted {
    com.shinsaikou.extensions.model.Extension$Untrusted getExtension();
}
-keepclassmembers class com.shinsaikou.extensions.model.Extension$Installed {
    long getVersionCode();
}
-keepclassmembers class com.shinsaikou.extensions.model.Extension$Untrusted {
    java.lang.String getPackageName();
}

# ---------------------------------------------------------------------------------------------
# Kept so the release build can be instrumented: the low RAM repro, `low_ram_repro_pass` on
# saikou_lowram_1_5g (ruling R61).
#
# Generated by tools/r8/check_test_references.py. Regenerate with:
#
#   python3 tools/r8/check_test_references.py \
#       app/build/outputs/apk/release/app-release.apk \
#       app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
#       --test-class com.shinsaikou.bench.LowRamReproTest --test-class com.shinsaikou.bench.LibraryCoverSeeder \
#       --test-class com.shinsaikou.bench.BenchFixtureSeeder --test-class com.shinsaikou.util.GateFragments \
#       --test-class com.shinsaikou.util.Args --keeps
#
# LowRamReproTest seeds a thousand favourites with covers, scrolls the library, then reads the
# benchmark strip to its end. It drives the library through the platform accessibility tree rather
# than a Compose test rule, which keeps the Compose semantics API out of this list; what remains is
# the members below, each removed because nothing shipped reaches it by name. The benchmark block
# above already covers what BenchFixtureSeeder and the reader hold reach.

# The graph entry points the seed and the first-launch state need: where a favourite's cover file
# lives, and the two pieces of state the tests seed before MainActivity opens (the battery
# exemption dialog and the crash banner, ruling R59).
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    eu.kanade.tachiyomi.data.cache.CoverCache getCoverCache();
    com.shinsaikou.core.common.preferences.BasePreferences getBasePreferences();
    com.shinsaikou.core.common.crash.CrashLog getCrashLog();
}
-keepclassmembers class com.shinsaikou.core.common.preferences.BasePreferences {
    tachiyomi.core.common.preference.Preference getBatteryExemptionRequested();
}
-keepclassmembers class com.shinsaikou.core.common.crash.CrashLog {
    void markExported();
}

# Read off the reader while the strip scrolls: the page the reader has selected, and the error a
# page that failed carries, which is how a decode that ran out of memory is told apart from one
# that failed for another reason.
-keepclassmembers class com.shinsaikou.reader.state.ReaderState {
    int getCurrentPage();
}
-keepclassmembers class com.shinsaikou.reader.pipeline.PageState$Error {
    java.lang.Throwable getError();
}

# Check 2 of the amended R61 reads the cover cache's level through the loader the graph hands every
# screen, rather than through an accessor added for the test (R39). All three are gone from the
# release build for the same reason: each has exactly one implementation, so R8 calls that
# implementation directly. `SaikouApp` answers Coil's singleton with `graph.imageLoader` and the
# memory guard clears the cache through the loader, and both calls were devirtualised, which leaves
# the graph's accessor and the loader's `memoryCache` with no caller by interface. `MemoryCache` is
# gone as a type: it was merged into its one implementation. Only the two members the test reads
# are named, so the rest of the interface is still R8's to remove.
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    coil3.ImageLoader getImageLoader();
}
-keepclassmembers interface coil3.ImageLoader {
    coil3.memory.MemoryCache getMemoryCache();
}
-keep interface coil3.memory.MemoryCache {
    long getSize();
    long getMaxSize();
}
