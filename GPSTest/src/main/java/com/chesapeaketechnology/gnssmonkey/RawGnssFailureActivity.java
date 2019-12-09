package com.chesapeaketechnology.gnssmonkey;

import com.android.gpstest.Application;
import com.android.gpstest.R;
import com.android.gpstest.util.PreferenceUtils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

/**
 * This failure activity is used to indicate to the user that their phone does not provide the
 * raw GNSS measurements that GPS Monkey requires.
 */
public class RawGnssFailureActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = Application.getPrefs();

        if (prefs.getBoolean(getString(R.string.pref_key_dark_theme), false)) {
            setTheme(R.style.AppTheme_Dark);
        }

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_failure);

        TextView descriptionTextView = findViewById(R.id.failureDescriptionTextView);
        descriptionTextView.setMovementMethod(LinkMovementMethod.getInstance());

        CheckBox rememberDecisionCheckBox = findViewById(R.id.failureRememberDecisionCheckBox);

        View buttonOk = findViewById(R.id.failureOkButton);
        if (buttonOk != null) {
            buttonOk.setOnClickListener(v -> {
                boolean checked = rememberDecisionCheckBox.isChecked();
                if (checked) {
                    PreferenceUtils.saveBoolean(Application.get().getString(R.string.pref_key_ignore_raw_gnss_failure), true);
                }
                finish();
            });
        }

        View buttonUninstall = findViewById(R.id.failureUninstallButton);
        if (buttonUninstall != null) {
            buttonUninstall.setOnClickListener(v -> {
                Uri packageUri = Uri.parse("package:" + RawGnssFailureActivity.this.getPackageName());
                try {
                    startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri));
                } catch (ActivityNotFoundException ignore) {
                }
            });
        }
    }
}
