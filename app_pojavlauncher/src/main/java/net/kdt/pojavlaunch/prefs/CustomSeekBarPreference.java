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
        //Note: since the max (setMax is a final function) is not taken into account properly, setting the min over the max may produce funky results
        super.setMin(min);
        if (min != mMin) mMin = min;
    }


    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        TextView titleTextView = (TextView) view.findViewById(android.R.id.title);
        titleTextView.setTextColor(Color.WHITE);

        mTextView = (TextView) view.findViewById(R.id.seekbar_value);
        mTextView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        SeekBar seekBar = (SeekBar) view.findViewById(R.id.seekbar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                progress += mMin;
                progress = progress / getSeekBarIncrement();
                progress = progress * getSeekBarIncrement();
                progress -= mMin;

                mTextView.setText(String.valueOf(progress + mMin));
                updateTextViewWithSuffix();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

                int progress = seekBar.getProgress() + mMin;
                progress /= getSeekBarIncrement();
                progress *= getSeekBarIncrement();
                progress -= mMin;

                setValue(progress + mMin);
                updateTextViewWithSuffix();
            }
        });

        // Tapping the value lets the user type the number directly instead of using the slider
        mTextView.setPaintFlags(mTextView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        mTextView.setOnClickListener(v -> showValueInputDialog());

        updateTextViewWithSuffix();
    }

    /** Shows a dialog to type the value as a number, validated against the min/max range */
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

        // Set the click listener after show() so an invalid value does not dismiss the dialog
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

                    // Same rounding as the slider so the value respects the increment
                    int increment = Math.max(1, getSeekBarIncrement());
                    value = ((value - min) / increment) * increment + min;

                    setValue(value);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    /**
     * Set a suffix to be appended on the TextView associated to the value
     * @param suffix The suffix to append as a String
     */
    public void setSuffix(String suffix) {
        this.mSuffix = suffix;
    }

    /**
     * Convenience function to set both min and max at the same time.
     * @param min The minimum value
     * @param max The maximum value
     */
    public void setRange(int min, int max){
        setMin(min);
        setMaxKeepIncrement(max);
    }

    public void setMaxKeepIncrement(int max) {
        super.setMax(max);
        setSeekBarIncrement(mIncrement);
    }


    private void updateTextViewWithSuffix(){
        if(!mTextView.getText().toString().endsWith(mSuffix)){
            mTextView.setText(String.format("%s%s", mTextView.getText(), mSuffix));
        }
    }
}
