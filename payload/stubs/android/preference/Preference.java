package android.preference; public class Preference { public Preference(android.content.Context c){}
 public void setOrder(int o){} public int getOrder(){return 0;} public void setTitle(CharSequence t){} public void setSummary(CharSequence s){} public void setKey(String k){} public String getKey(){return null;} public void setPersistent(boolean p){}
 public void setOnPreferenceChangeListener(OnPreferenceChangeListener l){} public void setOnPreferenceClickListener(OnPreferenceClickListener l){}
 public interface OnPreferenceChangeListener { boolean onPreferenceChange(Preference p, Object v); }
 public interface OnPreferenceClickListener { boolean onPreferenceClick(Preference p); } }
