package androidx.preference;

import android.content.Context;

/** Compile-only stub of the real class in the F1TV app; never dexed. */
public class Preference {
    public interface OnPreferenceChangeListener {
        boolean onPreferenceChange(Preference preference, Object newValue);
    }

    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }

    public Preference(Context context) { }
    public void setTitle(CharSequence title) { }
    public void setSummary(CharSequence summary) { }
    public void setKey(String key) { }
    public String getKey() { return null; }
    public void setPersistent(boolean persistent) { }
    public void setOrder(int order) { }
    public void setIconSpaceReserved(boolean reserved) { }
    public void setEnabled(boolean enabled) { }
    public void setSelectable(boolean selectable) { }
    public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener) { }
    public void setOnPreferenceClickListener(OnPreferenceClickListener listener) { }
}
