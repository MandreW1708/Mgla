package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaWhisperModelsManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.HashMap;

/**
 * Каталог ggml-моделей Whisper: скачать / выбрать / удалить.
 */
public class MglaWhisperModelsActivity extends MglaBaseSettingsActivity {

    private static final int ROW_SELECTED = 0;
    private static final int ROW_NOTE = 1;
    private static final int ROW_SHADOW = 2;
    private static final int ROW_HEADER = 3;
    private static final int FIRST_MODEL = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final HashMap<String, Integer> downloadPercent = new HashMap<>();

    public MglaWhisperModelsActivity() {
        this(null);
    }

    public MglaWhisperModelsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Модели Whisper");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(0, dp(8), 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.setSections();
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_SELECTED) {
                String id = MglaWhisperModelsManager.getSelectedModelId();
                if (id != null && !id.isEmpty() && MglaWhisperModelsManager.isDownloaded(context, id)) {
                    confirmDelete(context, id);
                }
                return;
            }
            int modelIndex = position - FIRST_MODEL;
            if (modelIndex < 0 || modelIndex >= MglaWhisperModelsManager.CATALOG.length) {
                return;
            }
            MglaWhisperModelsManager.ModelInfo info = MglaWhisperModelsManager.CATALOG[modelIndex];
            if (MglaWhisperModelsManager.isDownloaded(context, info.id)) {
                if (info.id.equals(MglaWhisperModelsManager.getSelectedModelId())) {
                    confirmDelete(context, info.id);
                } else {
                    MglaWhisperModelsManager.selectModel(info.id);
                    WhisperTranscriber.preloadSelected();
                    adapter.notifyDataSetChanged();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Модель выбрана").show();
                }
            } else if (MglaWhisperModelsManager.isDownloading(info.id)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Уже скачивается").show();
            } else {
                startDownload(context, info);
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void confirmDelete(Context context, String id) {
        if (getParentActivity() == null) {
            return;
        }
        MglaWhisperModelsManager.ModelInfo info = MglaWhisperModelsManager.find(id);
        String title = info != null ? info.title : id;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Удалить модель?");
        b.setMessage(title + " будет удалена с устройства.");
        b.setPositiveButton("Удалить", (d, w) -> {
            MglaWhisperModelsManager.deleteModel(context, id);
            adapter.notifyDataSetChanged();
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void startDownload(Context context, MglaWhisperModelsManager.ModelInfo info) {
        downloadPercent.put(info.id, 0);
        adapter.notifyDataSetChanged();
        MglaWhisperModelsManager.download(context, info, new MglaWhisperModelsManager.DownloadCallback() {
            @Override
            public void onProgress(long downloaded, long total) {
                int pct = total > 0 ? (int) (downloaded * 100 / total) : 0;
                downloadPercent.put(info.id, pct);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDone(File file) {
                downloadPercent.remove(info.id);
                WhisperTranscriber.preloadSelected();
                adapter.notifyDataSetChanged();
                BulletinFactory.of(MglaWhisperModelsActivity.this)
                    .createSimpleBulletin(R.raw.contact_check, "Модель скачана").show();
            }

            @Override
            public void onError(String message) {
                downloadPercent.remove(info.id);
                adapter.notifyDataSetChanged();
                BulletinFactory.of(MglaWhisperModelsActivity.this)
                    .createSimpleBulletin(R.raw.error, message != null ? message : "Ошибка").show();
            }
        });
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int p = holder.getAdapterPosition();
            return p == ROW_SELECTED || p >= FIRST_MODEL;
        }

        @Override
        public int getItemCount() {
            return FIRST_MODEL + MglaWhisperModelsManager.CATALOG.length + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW) {
                return 2;
            }
            if (position == ROW_NOTE || position == FIRST_MODEL + MglaWhisperModelsManager.CATALOG.length) {
                return 3;
            }
            if (position == ROW_HEADER) {
                return 4;
            }
            if (position == ROW_SELECTED) {
                return 1;
            }
            return 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 2) {
                view = new ShadowSectionCell(context);
            } else if (viewType == 3) {
                view = new TextInfoPrivacyCell(context);
            } else if (viewType == 4) {
                view = new HeaderCell(context, 22);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == 1) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                row.setPadding(dp(21), dp(12), dp(21), dp(12));

                TextView title = new TextView(context);
                title.setTag("title");
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                row.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                TextView desc = new TextView(context);
                desc.setTag("desc");
                desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                desc.setPadding(0, dp(2), 0, 0);
                row.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                LinearLayout statusRow = new LinearLayout(context);
                statusRow.setOrientation(LinearLayout.HORIZONTAL);
                statusRow.setGravity(Gravity.CENTER_VERTICAL);
                statusRow.setPadding(0, dp(6), 0, 0);

                TextView state = new TextView(context);
                state.setTag("state");
                state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
                statusRow.addView(state, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

                ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
                progress.setTag("progress");
                progress.setMax(100);
                progress.setVisibility(View.GONE);
                statusRow.addView(progress, LayoutHelper.createLinear(80, 8, Gravity.CENTER_VERTICAL));

                row.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                view = row;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == ROW_SELECTED) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Выбрана", MglaWhisperModelsManager.getSelectedModelTitle(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_NOTE) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                    "Модели скачиваются с HuggingFace (ggerganov/whisper.cpp) и хранятся только на устройстве. Для русского удобнее Base или Small.");
            } else if (position == ROW_HEADER) {
                ((HeaderCell) holder.itemView).setText("Каталог");
            } else if (position == FIRST_MODEL + MglaWhisperModelsManager.CATALOG.length) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                    "Нажмите на модель, чтобы скачать. Повторный тап по выбранной — удалить.");
            } else if (position >= FIRST_MODEL) {
                MglaWhisperModelsManager.ModelInfo info = MglaWhisperModelsManager.CATALOG[position - FIRST_MODEL];
                LinearLayout row = (LinearLayout) holder.itemView;
                TextView title = row.findViewWithTag("title");
                TextView desc = row.findViewWithTag("desc");
                TextView state = row.findViewWithTag("state");
                ProgressBar progress = row.findViewWithTag("progress");
                title.setText(info.title + " · " + MglaWhisperModelsManager.formatSize(info.sizeBytes));
                desc.setText(info.description);
                boolean downloaded = MglaWhisperModelsManager.isDownloaded(context, info.id);
                boolean selected = info.id.equals(MglaWhisperModelsManager.getSelectedModelId());
                Integer pct = downloadPercent.get(info.id);
                if (pct != null || MglaWhisperModelsManager.isDownloading(info.id)) {
                    int p = pct != null ? pct : 0;
                    state.setText("Скачивание " + p + "%");
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(p);
                } else if (selected && downloaded) {
                    state.setText("Выбрана · тап — удалить");
                    progress.setVisibility(View.GONE);
                } else if (downloaded) {
                    state.setText("Скачана · тап — выбрать");
                    progress.setVisibility(View.GONE);
                } else {
                    state.setText("Тап — скачать");
                    progress.setVisibility(View.GONE);
                }
            }
        }
    }
}
