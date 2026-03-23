-keep class com.nothingrecorder.** { *; }
-keep class com.nothingrecorder.utils.RecordingConfig { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
