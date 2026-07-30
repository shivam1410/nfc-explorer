# NFC Explorer release keep rules.
# Domain models are serialised into session exports; keep their names stable so
# exported JSON field names do not change between debug and release builds.
-keep class dev.shivam.nfcexplorer.domain.model.** { *; }
-keep class dev.shivam.nfcexplorer.logging.** { *; }
