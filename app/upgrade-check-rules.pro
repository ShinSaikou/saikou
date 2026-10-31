# Applied to the release only with -Psaikou.upgradeCheck: unit R1's in-place upgrade check (phase 3
# decision Q15) builds its release with it, and nothing else does, so the release the gate builds and
# the owner installs carries none of this.
#
# The check (com.shinsaikou.bench.InPlaceUpgradeCheckTest and InPlaceUpgrade) reads the database, the
# download root and the preferences through the platform, and reaches the application only for
# what the platform cannot answer. `tools/r8/check_test_references.py` resolved every member it
# reaches against the release APK and listed the first two below as absent: nothing shipped asks the
# graph for the secret store, and nothing shipped reads a secret yet. The secret has to be read
# through the store, because what the check asks is whether the store can still decrypt it after
# the install.
-keepclassmembers interface com.shinsaikou.di.AppGraph {
    com.shinsaikou.sync.SecretStore getSecretStore();
}
-keepclassmembers interface com.shinsaikou.sync.SecretStore {
    java.lang.String get(java.lang.String);
}

# The check reads saikou.db through the bundled SQLite the application uses (InPlaceUpgrade.query says
# why not the platform's), with a driver of its own. R8 merges BundledSQLiteDriver horizontally with
# unrelated classes and gives the merged constructor a class-id argument; the test APK's `new` was
# rewritten to that constructor with an id that selected another class's body, so the driver's
# extension map was never set and `open` threw NullPointerException on the API 28 image.
# check_test_references.py resolves the call and cannot see this. Keeping the class keeps it unmerged.
-keep class androidx.sqlite.driver.bundled.BundledSQLiteDriver {
    <init>();
    androidx.sqlite.SQLiteConnection open(java.lang.String);
}
