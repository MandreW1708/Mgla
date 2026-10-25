package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;

public class DownloadProgressIcon extends View implements NotificationCenter.NotificationCenterDelegate {

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final Path trayPath = new Path();

    private final int currentAccount;
    private final ArrayList<ProgressObserver> currentListeners = new ArrayList<>();
    private float progress;
    private float currentProgress;
    private float progressDt;
    private int currentColor;

    public DownloadProgressIcon(int currentAccount, Context context) {
        super(context);
        this.currentAccount = currentAccount;

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(2f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        paint.setStyle(Paint.Style.FILL);
        paint2.setStyle(Paint.Style.FILL);
    }

    public void updateColors() {
        currentColor = Theme.getColor(Theme.key_actionBarDefaultIcon);
        iconPaint.setColor(currentColor);
        paint.setColor(currentColor);
        paint2.setColor(currentColor);
        paint2.setAlpha(70);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getAlpha() == 0) {
            return;
        }

        int themeColor = Theme.getColor(Theme.key_actionBarDefaultIcon);
        if (currentColor != themeColor) {
            currentColor = themeColor;
            iconPaint.setColor(currentColor);
            paint.setColor(currentColor);
            paint2.setColor(currentColor);
            paint2.setAlpha(70);
        }

        final boolean isDownloading = !currentListeners.isEmpty();

        if (isDownloading && currentProgress != progress) {
            currentProgress += progressDt;
            if (progressDt > 0 && currentProgress > progress) {
                currentProgress = progress;
            } else if (progressDt < 0 && currentProgress < progress) {
                currentProgress = progress;
            } else {
                invalidate();
            }
        }

        final float cx = getMeasuredWidth() / 2f;
        // Смещаем иконку чуть выше только когда активен нижний индикатор прогресса
        final float cy = isDownloading
                ? (getMeasuredHeight() / 2f - AndroidUtilities.dp(2.5f))
                : (getMeasuredHeight() / 2f);

        // --- Аккуратная статичная векторная иконка загрузки (стрелка + лоток ~21x19dp) ---
        // 1. Вертикальный стержень стрелки
        canvas.drawLine(cx, cy - AndroidUtilities.dp(8f), cx, cy + AndroidUtilities.dp(3.0f), iconPaint);

        // 2. Наконечник стрелки (шеврон)
        arrowPath.reset();
        arrowPath.moveTo(cx - AndroidUtilities.dp(5.5f), cy - AndroidUtilities.dp(2.2f));
        arrowPath.lineTo(cx, cy + AndroidUtilities.dp(3.0f));
        arrowPath.lineTo(cx + AndroidUtilities.dp(5.5f), cy - AndroidUtilities.dp(2.2f));
        canvas.drawPath(arrowPath, iconPaint);

        // 3. Лоток под стрелкой со скругленными краями (ширина 19dp)
        final float trayLeft = cx - AndroidUtilities.dp(9.5f);
        final float trayRight = cx + AndroidUtilities.dp(9.5f);
        final float trayTop = cy + AndroidUtilities.dp(4.0f);
        final float trayBottom = cy + AndroidUtilities.dp(8.8f);
        final float cornerR = AndroidUtilities.dp(3.0f);

        trayPath.reset();
        trayPath.moveTo(trayLeft, trayTop);
        trayPath.lineTo(trayLeft, trayBottom - cornerR);
        trayPath.quadTo(trayLeft, trayBottom, trayLeft + cornerR, trayBottom);
        trayPath.lineTo(trayRight - cornerR, trayBottom);
        trayPath.quadTo(trayRight, trayBottom, trayRight, trayBottom - cornerR);
        trayPath.lineTo(trayRight, trayTop);
        canvas.drawPath(trayPath, iconPaint);

        // --- Индикатор прогресса внизу (только когда реально идет скачивание) ---
        if (isDownloading) {
            final float barY = getMeasuredHeight() - AndroidUtilities.dp(8.5f);
            final float barR = AndroidUtilities.dp(1.25f);
            final float startPadding = AndroidUtilities.dp(13f);
            final float totalWidth = getMeasuredWidth() - startPadding * 2;

            // Фоновая подложка трека
            AndroidUtilities.rectTmp.set(startPadding, barY - barR, getMeasuredWidth() - startPadding, barY + barR);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, barR, barR, paint2);

            // Активный прогресс скачивания
            final float activeWidth = Math.max(barR * 2, totalWidth * Math.max(0f, Math.min(1f, currentProgress)));
            AndroidUtilities.rectTmp.set(startPadding, barY - barR, startPadding + activeWidth, barY + barR);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, barR, barR, paint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateDownloadingListeners();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.onDownloadingFilesChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detachCurrentListeners();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
    }

    private void updateDownloadingListeners() {
        DownloadController downloadController = DownloadController.getInstance(currentAccount);
        HashMap<String, ProgressObserver> observerHashMap = new HashMap<>();
        for (int i = 0; i < currentListeners.size(); i++) {
            observerHashMap.put(currentListeners.get(i).fileName, currentListeners.get(i));
            downloadController.removeLoadingFileObserver(currentListeners.get(i));
        }
        currentListeners.clear();
        for (int i = 0; i < downloadController.downloadingFiles.size(); i++) {
            String filename = downloadController.downloadingFiles.get(i).getFileName();
            if (FileLoader.getInstance(currentAccount).isLoadingFile(filename)) {
                ProgressObserver progressObserver = observerHashMap.get(filename);
                if (progressObserver == null) {
                    progressObserver = new ProgressObserver(filename);
                }
                downloadController.addLoadingFileObserver(filename, progressObserver);
                currentListeners.add(progressObserver);
            }
        }
        if (currentListeners.isEmpty()) {
            progress = 0f;
            currentProgress = 0f;
            progressDt = 0f;
        }
        invalidate();
    }

    public void updateProgress() {
        if (currentListeners.isEmpty()) {
            progress = 0f;
            currentProgress = 0f;
            progressDt = 0f;
            invalidate();
            return;
        }
        long total = 0;
        long downloaded = 0;
        for (int i = 0; i < currentListeners.size(); i++) {
            total += currentListeners.get(i).total;
            downloaded += currentListeners.get(i).downloaded;
        }
        if (total == 0) {
            progress = 0f;
        } else {
            progress = downloaded / (float) total;
        }
        if (progress > 1f) {
            progress = 1f;
        } else if (progress < 0) {
            progress = 0;
        }

        progressDt = (progress - currentProgress) * 16f / 150f;
        invalidate();
    }

    private void detachCurrentListeners() {
        DownloadController downloadController = DownloadController.getInstance(currentAccount);
        for (int i = 0; i < currentListeners.size(); i++) {
            downloadController.removeLoadingFileObserver(currentListeners.get(i));
        }
        currentListeners.clear();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onDownloadingFilesChanged) {
            updateDownloadingListeners();
            updateProgress();
        }
    }

    private class ProgressObserver implements DownloadController.FileDownloadProgressListener {
        long total;
        long downloaded;
        private final String fileName;

        private ProgressObserver(String fileName) {
            this.fileName = fileName;
        }

        @Override
        public void onFailedDownload(String fileName, boolean canceled) {
        }

        @Override
        public void onSuccessDownload(String fileName) {
        }

        @Override
        public void onProgressDownload(String fileName, long downloadSize, long totalSize) {
            downloaded = downloadSize;
            total = totalSize;
            updateProgress();
        }

        @Override
        public void onProgressUpload(String fileName, long downloadSize, long totalSize, boolean isEncrypted) {
        }

        @Override
        public int getObserverTag() {
            return 0;
        }
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
    }
}
