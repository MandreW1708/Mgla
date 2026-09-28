package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MglaLocalModelsManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SearchField;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mgla: экран локальных ИИ-моделей — секции как в разделе «Искусственный
 * интеллект» (setSections(), закругления 16). Всё — один RecyclerListView:
 * выбранная модель, подпись про 8 ГБ, пилюля поиска и секции с одинаковыми
 * тенями 12dp между ними. Тап по скачанной — выбрать, тап по выбранной — удалить.
 */
public class MglaLocalModelsActivity extends BaseFragment {

    private static final int ROW_SELECTED = 0;
    private static final int ROW_NOTE = 1;
    private static final int ROW_SEARCH = 2;
    private static final int ROW_SHADOW = 3;
    private static final int ROW_HEADER = 4;
    private static final int ROW_MODEL = 5;
    private static final int ROW_RESULT = 6;
    private static final int ROW_FOOTER = 7;

    /** Фиксированные строки над секциями: выбранная, подпись, поиск, тень. */
    private static final int HEADER_ROWS = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextSettingsCell selectedCell;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<SearchResult> searchResults = new ArrayList<>();
    private boolean resultsVisible;
    /** Сколько строк сейчас занимает секция результатов (0 — скрыта). */
    private int resultsSectionSize;

    private final HashMap<String, TextView> stateViews = new HashMap<>();
    private final HashMap<String, ProgressBar> progressViews = new HashMap<>();
    private final HashMap<String, TextView> descViews = new HashMap<>();
    private final HashMap<String, Integer> downloadPercent = new HashMap<>();
    /** Кэш состояния моделей, чтобы бинд при прокрутке не дёргал диск и prefs. */
    private final HashMap<String, Boolean> downloadedCache = new HashMap<>();
    private String selectedIdCache = "";

    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchRunnable;
    private int searchToken;

    /** Размеры файлов репозиториев (id → байты), чтобы не дергать API повторно. */
    private static final HashMap<String, Long> sizeCache = new HashMap<>();
    private static final ExecutorService sizePool = Executors.newFixedThreadPool(6);

    private static final Pattern WEIGHT_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s?([mb])(?![a-z])");
    /** Модели тяжелее этого веса в поиске скрываются — не поместятся в память. */
    private static final long MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024;

    public MglaLocalModelsActivity() {
        this(null);
    }

    public MglaLocalModelsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Локальные модели");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = page;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(0, dp(8), 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.setSections();
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(animator);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        page.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        rebuildRows();
        refreshSelected(context);
        return fragmentView;
    }

    // ——— Поиск ———

    /** Поиск GGUF-репозиториев через публичный API HuggingFace. */
    private void performSearch(String query) {
        final int token = ++searchToken;
        new Thread(() -> {
            ArrayList<SearchResult> results = new ArrayList<>();
            HttpURLConnection conn = null;
            try {
                String url = "https://huggingface.co/api/models?search=" + URLEncoder.encode(query, "UTF-8")
                    + "&filter=gguf&sort=downloads&limit=20";
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mgla/1.0");
                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        body.append(line);
                    }
                }
                JSONArray arr = new JSONArray(body.toString());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id", "");
                    if (id.isEmpty()) {
                        continue;
                    }
                    results.add(new SearchResult(id, obj.optLong("downloads", 0)));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final ArrayList<SearchResult> out = results;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != searchToken || listView == null) {
                    return;
                }
                descViews.clear();
                searchResults.clear();
                for (SearchResult r : out) {
                    Long cached;
                    synchronized (sizeCache) {
                        cached = sizeCache.get(r.id);
                    }
                    if (cached == null || cached <= MAX_MODEL_BYTES) {
                        searchResults.add(r);
                    }
                }
                resultsVisible = true;
                replaceResultsSection();
                for (SearchResult r : searchResults) {
                    fetchModelSize(r);
                }
            });
        }, "MglaModelSearch").start();
    }

    private static class SearchResult {
        final String id;
        final long downloads;

        SearchResult(String id, long downloads) {
            this.id = id;
            this.downloads = downloads;
        }
    }

    /** Имя модели без аккаунта: unsloth/gemma-3-4b-it-GGUF → gemma-3-4b-it-GGUF. */
    private static String shortName(String repoId) {
        int slash = repoId.lastIndexOf('/');
        return slash >= 0 ? repoId.substring(slash + 1) : repoId;
    }

    /** Вес модели из имени: 0.6B, 135M, E4B и т.п. */
    private static String extractWeight(String name) {
        Matcher m = WEIGHT_PATTERN.matcher(name);
        if (m.find()) {
            return m.group(1) + m.group(2).toUpperCase();
        }
        return null;
    }

    /** Подпись строки результата: скачивания, вес и (если известен) размер файла. */
    private static String resultSubtitle(SearchResult result, Long fileSize) {
        String weight = extractWeight(result.id);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d скачиваний", result.downloads).replace(',', ' '));
        sb.append(" • вес: ").append(weight != null ? weight : "—");
        if (fileSize != null && fileSize > 0) {
            sb.append(" • ").append(MglaLocalModelsManager.formatSize(fileSize));
        }
        return sb.toString();
    }

    /** Размер GGUF-файла репозитория (приоритет Q4_K_M → Q4), фоном. */
    private void fetchModelSize(SearchResult result) {
        sizePool.execute(() -> {
            Long size;
            synchronized (sizeCache) {
                size = sizeCache.get(result.id);
            }
            if (size == null) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL("https://huggingface.co/api/models/" + result.id + "?blobs=true").openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "Mgla/1.0");
                    if (conn.getResponseCode() != 200) {
                        throw new Exception("HTTP " + conn.getResponseCode());
                    }
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            body.append(line);
                        }
                    }
                    JSONArray siblings = new JSONObject(body.toString()).optJSONArray("siblings");
                    String fileName = chooseGguf(siblings);
                    if (fileName != null && siblings != null) {
                        for (int i = 0; i < siblings.length(); i++) {
                            JSONObject sib = siblings.optJSONObject(i);
                            if (fileName.equals(sib.optString("rfilename", ""))) {
                                long s = sib.optLong("size", 0);
                                if (s > 0) {
                                    size = s;
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                if (size != null) {
                    synchronized (sizeCache) {
                        sizeCache.put(result.id, size);
                    }
                }
            }
            final Long outSize = size;
            if (outSize == null) {
                return;
            }
            final String storageId = MglaLocalModelsManager.sanitizeId(result.id);
            AndroidUtilities.runOnUIThread(() -> {
                if (outSize > MAX_MODEL_BYTES) {
                    for (int i = searchResults.size() - 1; i >= 0; i--) {
                        if (searchResults.get(i).id.equals(result.id)) {
                            searchResults.remove(i);
                            replaceResultsSection();
                            break;
                        }
                    }
                    return;
                }
                TextView desc = descViews.get(storageId);
                if (desc != null && desc.getParent() != null) {
                    desc.setText(resultSubtitle(result, outSize));
                }
            });
        });
    }

    /** Выбор GGUF-файла из siblings: приоритет Q4_K_M, затем любой Q4, иначе первый. */
    private static String chooseGguf(JSONArray siblings) {
        if (siblings == null) {
            return null;
        }
        String first = null;
        String q4 = null;
        String q4km = null;
        for (int i = 0; i < siblings.length(); i++) {
            String name = siblings.optJSONObject(i).optString("rfilename", "");
            if (!name.endsWith(".gguf")) {
                continue;
            }
            if (first == null) {
                first = name;
            }
            String lower = name.toLowerCase();
            if (lower.contains("q4_k_m")) {
                q4km = name;
            } else if (q4 == null && lower.contains("q4")) {
                q4 = name;
            }
        }
        return q4km != null ? q4km : (q4 != null ? q4 : first);
    }

    // ——— Состав списка ———

    private static class Row {
        int type;
        String header;
        MglaLocalModelsManager.ModelInfo model;
        SearchResult result;
        boolean badge;
    }

    private Context safeContext() {
        Context context = getParentActivity();
        return context != null ? context : org.telegram.messenger.ApplicationLoader.applicationContext;
    }

    /** Строки секции результатов: заголовок + результаты + тень. */
    private ArrayList<Row> resultRows() {
        ArrayList<Row> list = new ArrayList<>();
        Row h = new Row();
        h.type = ROW_HEADER;
        h.header = searchResults.isEmpty() ? "Ничего не найдено" : "Результаты поиска";
        list.add(h);
        for (SearchResult r : searchResults) {
            Row row = new Row();
            row.type = ROW_RESULT;
            row.result = r;
            list.add(row);
        }
        list.add(shadow());
        return list;
    }

    /** Строки «Скачанные» + «Рекомендованные» + подсказка. */
    private ArrayList<Row> baseRows(Context context) {
        ArrayList<Row> list = new ArrayList<>();
        ArrayList<MglaLocalModelsManager.ModelInfo> downloaded = downloadedModels(context);
        if (!downloaded.isEmpty()) {
            Row h = new Row();
            h.type = ROW_HEADER;
            h.header = "Скачанные";
            list.add(h);
            for (MglaLocalModelsManager.ModelInfo m : downloaded) {
                Row row = new Row();
                row.type = ROW_MODEL;
                row.model = m;
                list.add(row);
            }
            list.add(shadow());
        }
        Row h = new Row();
        h.type = ROW_HEADER;
        h.header = "Рекомендованные";
        list.add(h);
        for (MglaLocalModelsManager.ModelInfo m : MglaLocalModelsManager.CATALOG) {
            Row row = new Row();
            row.type = ROW_MODEL;
            row.model = m;
            row.badge = true;
            list.add(row);
        }
        list.add(shadow());
        Row footer = new Row();
        footer.type = ROW_FOOTER;
        list.add(footer);
        return list;
    }

    /** Появление/замена секции результатов — блоки плавно съезжают вниз. */
    private void replaceResultsSection() {
        if (adapter == null) {
            return;
        }
        if (!resultsVisible) {
            hideResults();
            return;
        }
        ArrayList<Row> section = resultRows();
        if (resultsSectionSize == 0) {
            rows.addAll(HEADER_ROWS, section);
            resultsSectionSize = section.size();
            adapter.notifyItemRangeInserted(HEADER_ROWS, section.size());
        } else {
            int oldSize = Math.min(resultsSectionSize, rows.size() - HEADER_ROWS);
            if (oldSize > 0) {
                rows.subList(HEADER_ROWS, HEADER_ROWS + oldSize).clear();
            }
            rows.addAll(HEADER_ROWS, section);
            resultsSectionSize = section.size();
            if (oldSize > 0) {
                adapter.notifyItemRangeRemoved(HEADER_ROWS, oldSize);
            }
            adapter.notifyItemRangeInserted(HEADER_ROWS, section.size());
        }
    }

    /** Поле поиска очищено — секция уезжает, блоки возвращаются наверх. */
    private void hideResults() {
        resultsVisible = false;
        searchResults.clear();
        if (adapter != null && resultsSectionSize > 0) {
            int oldSize = Math.min(resultsSectionSize, rows.size() - HEADER_ROWS);
            resultsSectionSize = 0;
            if (oldSize > 0) {
                rows.subList(HEADER_ROWS, HEADER_ROWS + oldSize).clear();
                adapter.notifyItemRangeRemoved(HEADER_ROWS, oldSize);
            }
        } else {
            resultsSectionSize = 0;
        }
    }

    private void refreshDownloadedCache(Context context) {
        downloadedCache.clear();
        for (MglaLocalModelsManager.ModelInfo m : MglaLocalModelsManager.CATALOG) {
            downloadedCache.put(m.id, MglaLocalModelsManager.isDownloaded(context, m.id));
        }
        for (String entry : MglaLocalModelsManager.customModels()) {
            int sep = entry.indexOf('|');
            String id = sep > 0 ? entry.substring(0, sep) : entry;
            downloadedCache.put(id, MglaLocalModelsManager.isDownloaded(context, id));
        }
        selectedIdCache = MglaLocalModelsManager.getSelectedModelId();
    }

    /** Полная пересборка (выбор/удаление/скачивание) без сдвига. */
    private void rebuildRows() {
        refreshDownloadedCache(safeContext());
        ArrayList<Row> fresh = new ArrayList<>();
        Row selected = new Row();
        selected.type = ROW_SELECTED;
        fresh.add(selected);
        Row note = new Row();
        note.type = ROW_NOTE;
        fresh.add(note);
        Row search = new Row();
        search.type = ROW_SEARCH;
        fresh.add(search);
        fresh.add(shadow());
        if (resultsVisible) {
            ArrayList<Row> section = resultRows();
            fresh.addAll(section);
            resultsSectionSize = section.size();
        } else {
            resultsSectionSize = 0;
        }
        fresh.addAll(baseRows(safeContext()));
        rows.clear();
        rows.addAll(fresh);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static Row shadow() {
        Row row = new Row();
        row.type = ROW_SHADOW;
        return row;
    }

    private ArrayList<MglaLocalModelsManager.ModelInfo> downloadedModels(Context context) {
        ArrayList<MglaLocalModelsManager.ModelInfo> list = new ArrayList<>();
        for (MglaLocalModelsManager.ModelInfo m : MglaLocalModelsManager.CATALOG) {
            if (MglaLocalModelsManager.isDownloaded(context, m.id)) {
                list.add(m);
            }
        }
        for (String entry : MglaLocalModelsManager.customModels()) {
            int sep = entry.indexOf('|');
            String id = sep > 0 ? entry.substring(0, sep) : entry;
            String title = sep > 0 ? shortName(entry.substring(sep + 1)) : id;
            if (MglaLocalModelsManager.isDownloaded(context, id)) {
                list.add(new MglaLocalModelsManager.ModelInfo(id, title, "Сообщество HuggingFace", null, 0));
            }
        }
        return list;
    }

    // ——— Адаптер ———

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_MODEL || type == ROW_RESULT;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_SELECTED:
                    selectedCell = new TextSettingsCell(context);
                    selectedCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = selectedCell;
                    break;
                case ROW_NOTE:
                    TextInfoPrivacyCell note = new TextInfoPrivacyCell(context);
                    note.setText("В выдаче скрываются модели тяжелее 8 ГБ — они не поместятся в память устройства.");
                    view = note;
                    break;
                case ROW_SEARCH:
                    SearchField searchField = new SearchField(context, false, 14, null) {
                        @Override
                        public void onTextChange(String text) {
                            searchHandler.removeCallbacks(searchRunnable);
                            String query = text == null ? "" : text.trim();
                            if (query.length() < 3) {
                                hideResults();
                                return;
                            }
                            final String q = query;
                            searchRunnable = () -> performSearch(q);
                            searchHandler.postDelayed(searchRunnable, 600);
                        }
                    };
                    searchField.setHint("Поиск на HuggingFace");
                    searchField.setTag(RecyclerListView.TAG_NOT_SECTION);
                    view = searchField;
                    break;
                case ROW_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case ROW_HEADER:
                    HeaderCell header = new HeaderCell(context, 22);
                    header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = header;
                    break;
                case ROW_FOOTER:
                    TextInfoPrivacyCell footer = new TextInfoPrivacyCell(context);
                    footer.setText("Тап по скачанной модели — выбрать. Тап по выбранной — удалить.");
                    view = footer;
                    break;
                default:
                    view = buildRow(context);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == ROW_SELECTED) {
                // Секция обновляется при бинде: refreshSelected в createView
                // приходит раньше создания ячейки и терялся.
                refreshSelected(holder.itemView.getContext());
            } else if (row.type == ROW_HEADER) {
                ((HeaderCell) holder.itemView).setText(row.header);
            } else if (row.type == ROW_MODEL) {
                bindModelRow(holder.itemView, row.model, row.badge);
            } else if (row.type == ROW_RESULT) {
                bindResultRow(holder.itemView, row.result);
            }
        }
    }

    // ——— Строки ———

    /** Разметка строки: заголовок+плашка+статус, подпись, прогресс. */
    private View buildRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(21), dp(10), dp(21), dp(10));
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        top.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL));

        TextView badge = createBadge(context);
        top.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, dp(8), 0));

        TextView state = new TextView(context);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        top.addView(state, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView desc = new TextView(context);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        row.addView(progress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(4), 0, 6, 0, 0));

        RowViews views = new RowViews();
        views.title = title;
        views.badge = badge;
        views.state = state;
        views.desc = desc;
        views.progress = progress;
        row.setTag(views);
        return row;
    }

    private static class RowViews {
        TextView title;
        TextView badge;
        TextView state;
        TextView desc;
        ProgressBar progress;
    }

    private void bindModelRow(View view, MglaLocalModelsManager.ModelInfo model, boolean badge) {
        LinearLayout row = (LinearLayout) view;
        Context context = row.getContext();
        RowViews views = (RowViews) row.getTag();
        views.title.setText(model.title);
        views.badge.setVisibility(badge ? View.VISIBLE : View.GONE);
        String weight = extractWeight(model.title);
        String size = model.sizeBytes > 0 ? MglaLocalModelsManager.formatSize(model.sizeBytes) : null;
        if (weight != null && size != null) {
            views.desc.setText(model.description + " • вес: " + weight + " • " + size);
        } else if (weight != null) {
            views.desc.setText(model.description + " • вес: " + weight);
        } else if (size != null) {
            views.desc.setText(model.description + " • " + size);
        } else {
            views.desc.setText(model.description);
        }
        registerRow(views, model.id);
        updateRowState(context, model.id);
        row.setOnClickListener(v -> onModelClick(context, model, model.title));
    }

    private void bindResultRow(View view, SearchResult result) {
        LinearLayout row = (LinearLayout) view;
        Context context = row.getContext();
        String storageId = MglaLocalModelsManager.sanitizeId(result.id);
        MglaLocalModelsManager.ModelInfo info = new MglaLocalModelsManager.ModelInfo(
            storageId, shortName(result.id), "Сообщество HuggingFace", null, 0);
        RowViews views = (RowViews) row.getTag();
        views.title.setText(shortName(result.id));
        views.badge.setVisibility(View.GONE);
        Long cached;
        synchronized (sizeCache) {
            cached = sizeCache.get(result.id);
        }
        views.desc.setText(resultSubtitle(result, cached));
        registerRow(views, storageId);
        updateRowState(context, storageId);
        row.setOnClickListener(v -> onModelClick(context, info, result.id));
    }

    private void registerRow(RowViews views, String id) {
        stateViews.put(id, views.state);
        progressViews.put(id, views.progress);
        descViews.put(id, views.desc);
    }

    private TextView createBadge(Context context) {
        TextView badge = new TextView(context);
        badge.setText("РЕКОМЕНДУЕТСЯ");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(0xFF4CAF50);
        badge.setBackground(bg);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        return badge;
    }

    private void updateRowState(Context context, String id) {
        TextView state = stateViews.get(id);
        ProgressBar progress = progressViews.get(id);
        if (state == null || progress == null) {
            return;
        }
        Integer percent = downloadPercent.get(id);
        if (MglaLocalModelsManager.isDownloading(id) && percent != null) {
            state.setText(percent + "%");
            state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            progress.setIndeterminate(false);
            progress.setProgress(percent);
            progress.setVisibility(View.VISIBLE);
        } else if (MglaLocalModelsManager.isDownloading(id)) {
            state.setText("Скачивание…");
            state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
        } else {
            progress.setIndeterminate(false);
            progress.setVisibility(View.GONE);
            Boolean downloaded = downloadedCache.get(id);
            if (downloaded == null) {
                downloaded = MglaLocalModelsManager.isDownloaded(context, id);
            }
            if (downloaded) {
                boolean selected = id.equals(selectedIdCache != null && !selectedIdCache.isEmpty()
                    ? selectedIdCache : MglaLocalModelsManager.getSelectedModelId());
                state.setText(selected ? "Выбрана ✓" : "Скачана");
                state.setTextColor(Theme.getColor(selected ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText));
            } else {
                state.setText("Скачать");
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            }
        }
    }

    private void refreshAllStates(Context context) {
        refreshDownloadedCache(context);
        for (String id : new ArrayList<>(stateViews.keySet())) {
            updateRowState(context, id);
        }
        refreshSelected(context);
        rebuildRows();
    }

    /** Верхняя строка: какая модель сейчас выбрана. */
    private void refreshSelected(Context context) {
        if (selectedCell == null) {
            return;
        }
        File loaded = MglaLocalModelsManager.getLoadedModel(context);
        if (loaded == null) {
            selectedCell.setTextAndValue("Выбранная модель", "не выбрана", false);
            return;
        }
        String id = MglaLocalModelsManager.getSelectedModelId();
        String title = id;
        for (MglaLocalModelsManager.ModelInfo m : MglaLocalModelsManager.CATALOG) {
            if (m.id.equals(id)) {
                title = m.title;
                break;
            }
        }
        for (String entry : MglaLocalModelsManager.customModels()) {
            if (entry.startsWith(id + "|")) {
                title = shortName(entry.substring(id.length() + 1));
                break;
            }
        }
        String weight = extractWeight(title);
        selectedCell.setTextAndValue("Выбранная модель",
            title + " (" + MglaLocalModelsManager.formatSize(loaded.length())
                + (weight != null ? " • " + weight : "") + ")", false);
    }

    // ——— Действия ———

    private void onModelClick(Context context, MglaLocalModelsManager.ModelInfo model, String displayTitle) {
        if (MglaLocalModelsManager.isDownloading(model.id)) {
            return;
        }
        if (MglaLocalModelsManager.isDownloaded(context, model.id)) {
            if (model.id.equals(MglaLocalModelsManager.getSelectedModelId())) {
                confirmDelete(context, model, displayTitle);
            } else {
                MglaLocalModelsManager.selectModel(model.id);
                refreshAllStates(context);
            }
            return;
        }
        startDownload(context, model, displayTitle);
    }

    private void startDownload(Context context, MglaLocalModelsManager.ModelInfo model, String displayTitle) {
        TextView state = stateViews.get(model.id);
        ProgressBar progress = progressViews.get(model.id);
        if (model.url == null) {
            // Пользовательская модель: сначала узнаём имя GGUF-файла в репозитории.
            if (state != null) {
                state.setText("Поиск файла…");
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            }
            resolveAndDownload(context, model, displayTitle);
            return;
        }
        downloadPercent.put(model.id, 0);
        if (state != null) {
            state.setText("0%");
            state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setProgress(0);
            progress.setVisibility(View.VISIBLE);
        }
        MglaLocalModelsManager.download(context, model, new MglaLocalModelsManager.DownloadCallback() {
            @Override
            public void onProgress(long downloaded, long total) {
                int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
                downloadPercent.put(model.id, percent);
                TextView s = stateViews.get(model.id);
                ProgressBar p = progressViews.get(model.id);
                if (s != null) {
                    s.setText(percent + "%");
                }
                if (p != null) {
                    p.setIndeterminate(false);
                    p.setProgress(percent);
                }
            }

            @Override
            public void onDone(File file) {
                downloadPercent.remove(model.id);
                refreshAllStates(context);
            }

            @Override
            public void onError(String message) {
                downloadPercent.remove(model.id);
                refreshAllStates(context);
                if (getParentActivity() != null) {
                    BulletinFactory.of(MglaLocalModelsActivity.this).createSimpleBulletin(R.raw.error, "Ошибка скачивания: " + message).show();
                }
            }
        });
    }

    /** /api/models/<id> → siblings → первый .gguf (приоритет Q4_K_M, затем Q4). */
    private void resolveAndDownload(Context context, MglaLocalModelsManager.ModelInfo model, String displayTitle) {
        new Thread(() -> {
            String fileName = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://huggingface.co/api/models/" + displayTitle).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mgla/1.0");
                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        body.append(line);
                    }
                }
                fileName = chooseGguf(new JSONObject(body.toString()).optJSONArray("siblings"));
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String chosen = fileName;
            AndroidUtilities.runOnUIThread(() -> {
                if (chosen == null) {
                    updateRowState(context, model.id);
                    if (getParentActivity() != null) {
                        BulletinFactory.of(MglaLocalModelsActivity.this).createSimpleBulletin(R.raw.error, "В репозитории нет GGUF-файла").show();
                    }
                    return;
                }
                String url = "https://huggingface.co/" + displayTitle + "/resolve/main/" + chosen;
                MglaLocalModelsManager.addCustomModel(model.id + "|" + displayTitle);
                MglaLocalModelsManager.ModelInfo full = new MglaLocalModelsManager.ModelInfo(
                    model.id, shortName(displayTitle), "Сообщество HuggingFace", url, 0);
                startDownload(context, full, displayTitle);
            });
        }, "MglaModelResolve").start();
    }

    private void confirmDelete(Context context, MglaLocalModelsManager.ModelInfo model, String displayTitle) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Удалить модель?");
        builder.setMessage("«" + displayTitle + "» будет удалена с устройства. При желании её можно скачать заново.");
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            MglaLocalModelsManager.deleteModel(context, model.id);
            MglaLocalModelsManager.forgetCustomModel(model.id);
            Context appContext = context.getApplicationContext();
            if (MglaLocalModelsManager.getLoadedModel(appContext) == null) {
                for (MglaLocalModelsManager.ModelInfo other : MglaLocalModelsManager.CATALOG) {
                    if (MglaLocalModelsManager.isDownloaded(appContext, other.id)) {
                        MglaLocalModelsManager.selectModel(other.id);
                        break;
                    }
                }
            }
            refreshAllStates(context);
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }
}
