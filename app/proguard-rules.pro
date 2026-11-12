# Add project specific ProGuard rules here.

# Office previews never use POI's desktop drawing/diagnostic or OSGi APIs.
-dontwarn java.awt.**
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.jspecify.annotations.**
