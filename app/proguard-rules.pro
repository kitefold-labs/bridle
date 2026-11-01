# sshj and BouncyCastle are loaded reflectively (JCA providers, algorithm factories).
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn net.i2p.crypto.eddsa.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
# sshj's optional Kerberos/GSSAPI auth (AuthGssApiWithMic) references JDK-only classes that
# don't exist on Android. Bridle never uses GSSAPI auth.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
