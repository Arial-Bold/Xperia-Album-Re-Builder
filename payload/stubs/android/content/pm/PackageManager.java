package android.content.pm; public abstract class PackageManager {
 public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0, COMPONENT_ENABLED_STATE_ENABLED = 1, COMPONENT_ENABLED_STATE_DISABLED = 2, DONT_KILL_APP = 1;
 public abstract void setComponentEnabledSetting(android.content.ComponentName c, int state, int flags);
 public abstract int getComponentEnabledSetting(android.content.ComponentName c);
 public abstract ActivityInfo getActivityInfo(android.content.ComponentName c, int flags) throws NameNotFoundException;
 public static class NameNotFoundException extends Exception { } }
