package androidx.preference;

import android.content.Context;

/** Compile-only stub of the real class in the F1TV app; never dexed. */
public abstract class PreferenceGroup extends Preference {
    public PreferenceGroup(Context context) { super(context); }
    public boolean addPreference(Preference preference) { return false; }
    public <T extends Preference> T findPreference(CharSequence key) { return null; }
}
