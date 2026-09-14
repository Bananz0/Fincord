# The native library looks both of these up by name: the JNI entry points are named after
# NativeAnalyzer's package and class, and automix_jni.c constructs a TrackAnalysis through
# FindClass and a GetMethodID on its constructor signature. R8 renaming either one turns a working
# analysis into an UnsatisfiedLinkError or a NoSuchMethodError at the first transition, which is a
# long way from where the mistake was made.
-keepclasseswithmembernames,includedescriptorclasses class uk.akane.accord.automix.NativeAnalyzer {
    native <methods>;
}
-keep class uk.akane.accord.automix.TrackAnalysis {
    <init>(...);
}
