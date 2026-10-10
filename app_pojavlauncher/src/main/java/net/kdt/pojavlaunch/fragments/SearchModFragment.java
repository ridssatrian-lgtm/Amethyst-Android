package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";
    /** Optional int argument: which kind of content to show first (one of Constants.CONTENT_*) */
    public static final String ARG_CONTENT_TYPE = "content_type";
    private View mOverlay;
    private float mOverlayTopCache; // Padding cache reduce resource lookup

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private TextView mTargetTextView;
    private RadioGroup mTypeGroup;
    private ColorStateList mDefaultTextColor;

    private ModpackApi modpackApi;

    private final SearchFilters mSearchFilters;

    public SearchModFragment(){
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        modpackApi = new CommonApi(context.getString(R.string.curseforge_api_key));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // You can only access resources after attaching to current context
        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.search_mod_overlay);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        mTargetTextView = view.findViewById(R.id.search_mod_target_text);
        mTypeGroup = view.findViewById(R.id.search_mod_type_group);

        mDefaultTextColor = mStatusTextView.getTextColors();

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);

        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mOverlay.post(()->{
           int overlayHeight = mOverlay.getHeight();
           mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                   mRecyclerview.getPaddingTop() + overlayHeight,
                   mRecyclerview.getPaddingRight(),
                   mRecyclerview.getPaddingBottom());
        });
        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        // Pick the content type that was requested by whoever opened this screen
        Bundle arguments = getArguments();
        if (arguments != null) {
            mSearchFilters.contentType = arguments.getInt(ARG_CONTENT_TYPE, Constants.CONTENT_MODPACK);
        }
        mTypeGroup.check(getTypeButtonId(mSearchFilters.contentType));
        updateContentTypeUi();
        // Set the listener after the initial check so it does not trigger a second search
        mTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int contentType = getContentType(checkedId);
            if (contentType == mSearchFilters.contentType) return;
            mSearchFilters.contentType = contentType;
            updateContentTypeUi();
            searchMods(mSearchEditText.getText().toString());
        });

        searchMods(null);
    }

    private static int getTypeButtonId(int contentType) {
        switch (contentType) {
            case Constants.CONTENT_MOD:
                return R.id.search_mod_type_mod;
            case Constants.CONTENT_SHADER:
                return R.id.search_mod_type_shader;
            case Constants.CONTENT_RESOURCEPACK:
                return R.id.search_mod_type_resourcepack;
            default:
                return R.id.search_mod_type_modpack;
        }
    }

    private static int getContentType(int buttonId) {
        if (buttonId == R.id.search_mod_type_mod) return Constants.CONTENT_MOD;
        if (buttonId == R.id.search_mod_type_shader) return Constants.CONTENT_SHADER;
        if (buttonId == R.id.search_mod_type_resourcepack) return Constants.CONTENT_RESOURCEPACK;
        return Constants.CONTENT_MODPACK;
    }

    private int getTypeLabelId(int contentType) {
        switch (contentType) {
            case Constants.CONTENT_MOD:
                return R.string.content_type_mod;
            case Constants.CONTENT_SHADER:
                return R.string.content_type_shader;
            case Constants.CONTENT_RESOURCEPACK:
                return R.string.content_type_resourcepack;
            default:
                return R.string.content_type_modpack;
        }
    }

    /** Update the hint and the "where will it be saved" line for the selected content type */
    private void updateContentTypeUi() {
        String label = getString(getTypeLabelId(mSearchFilters.contentType));
        mSearchEditText.setHint(getString(R.string.hint_search_content, label));

        if (mSearchFilters.contentType == Constants.CONTENT_MODPACK) {
            // Modpacks make their own profile, no target to show
            mTargetTextView.setVisibility(View.GONE);
            return;
        }
        String profileName = getCurrentProfileName();
        if (profileName == null) {
            mTargetTextView.setVisibility(View.GONE);
        } else {
            mTargetTextView.setText(getString(R.string.content_target_profile, profileName));
            mTargetTextView.setVisibility(View.VISIBLE);
        }
    }

    private String getCurrentProfileName() {
        try {
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            if (profile.name != null && !profile.name.isEmpty()) return profile.name;
            return LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        } catch (RuntimeException e) {
            // The selected profile does not exist (anymore)
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
    }

    @Override
    public void onSearchFinished() {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_content_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_content_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        // Hand a copy to the adapter: it keeps using the filters for the next pages, and changing
        // the content type while a search is running must not alter that search halfway through
        SearchFilters filters = new SearchFilters();
        filters.name = mSearchFilters.name;
        filters.mcVersion = mSearchFilters.mcVersion;
        filters.contentType = mSearchFilters.contentType;
        mModItemAdapter.performSearchQuery(filters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        // setup the view behavior
        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mApplyButton != null;

            // Setup the expendable list behavior
            mSelectVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true, (id, snapshot)-> mSelectedVersion.setText(id)));

            // Apply visually all the current settings
            mSelectedVersion.setText(mSearchFilters.mcVersion);

            // Apply the new settings
            mApplyButton.setOnClickListener(v -> {
                mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });


        dialog.show();
    }
}
