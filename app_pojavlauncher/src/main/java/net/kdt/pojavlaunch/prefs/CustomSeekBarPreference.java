package net.kdt.pojavlaunch.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

import net.kdt.pojavlaunch.R;

import java.util.Locale;

public class CustomSeekBarPreference extends SeekBarPreference {

    /** The suffix displayed */
    private String mSuffix = "";
    /** Custom minimum value to provide the same behavior as the usual setMin */
    private int mMin;
    /** The textview associated by default to the preference */
    private TextView mTextView;
    /** Seekbar increment in case the max gets set */
    private final int mIncrement;

    @SuppressLint("PrivateResource")
    public CustomSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        try (TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.SeekBarPreference, defStyleAttr, defStyleRes)) {
            mMin = a.getInt(R.styleable.SeekBarPreference_min, 0);
            mIncrement = a.getInt(R.styleable.SeekBarPreference_seekBarIncrement, 0);
        }
    }

    public CustomSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CustomSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.seekBarPreferenceStyle);
    }

    @SuppressWarnings("unused") public CustomSeekBarPreference(Context context) {
        this(context, null);
    }

    @Override
    public void setMin(int min) {
        // Note: setMin above the max may produce unexpected results.
        super.setMin(min);
        if (min != mMin) mMin = min;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        TextView titleTextView = (TextView) view.findViewById(android.R.id.title);
        if (titleTextView != null) titleTextView.setTextColor(Color.WHITE);

        mTextView = (TextView) view.findViewById(R.id.seekbar_value);
        SeekBar seekBar = (SeekBar) view.findViewById(R.id.seekbar);
        if (mTextView == null || seekBar == null) return;

        mTextView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                progress += mMin;
                int increment = Math.max(1, getSeekBarIncrement());
                progress = (progress / increment) * increment;
                progress -= mMin;
                mTextView.setText(String.valueOf(progress + mMin));
                updateTextViewWithSuffix();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress() + mMin;
                int increment = Math.max(1, getSeekBarIncrement());
                progress = (progress / increment) * increment;
                setValue(progress);
                updateTextViewWithSuffix();
            }
        });

        // Tap the value to enter an exact number in a dialog.
        mTextView.setPaintFlags(mTextView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        mTextView.setOnClickListener(v -> showValueInputDialog());
        updateTextViewWithSuffix();
    }

    /** Shows a numeric input dialog validated against the preference's min/max range. */
    private void showValueInputDialog() {
        final Context context = getContext();
        final int min = getMin();
        final int max = getMax();

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine();
        input.setText(String.valueOf(getValue()));
        input.setSelection(input.getText().length());

        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        input.setError(context.getString(R.string.preference_seekbar_input_invalid, min, max));
                        return;
                    }
                    if (value < min || value > max) {
                        input.setError(context.getString(R.string.preference_seekbar_input_invalid, min, max));
                        return;
                    }

                    int increment = Math.max(1, getSeekBarIncrement());
                    value = ((value - min) / increment) * increment + min;
                    setValue(value);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    /** Set a suffix appended to the formatted value. */
    public void setSuffix(String suffix) {
        this.mSuffix = suffix;
    }

    /** Set the minimum and maximum together. */
    public void setRange(int min, int max) {
        setMin(min);
        setMaxKeepIncrement(max);
    }

    public void setMaxKeepIncrement(int max) {
        super.setMax(max);
        setSeekBarIncrement(mIncrement);
    }

    private void updateTextViewWithSuffix() {
        if (mTextView == null) return;
        String raw = mTextView.getText().toString().replaceFirst("[^0-9].*$", "").trim();
        if (raw.isEmpty()) return;
        try {
            int value = Integer.parseInt(raw);
            String gigabytes = String.format(Locale.getDefault(), "%.1f", value / 1024.0);
            mTextView.setText(String.format(Locale.getDefault(), "%d MB ( %s GB )%s",
                    value, gigabytes, mSuffix));
        } catch (NumberFormatException ignored) {
            // Keep the existing text if it is not a valid numeric value.
        }
    }
}
