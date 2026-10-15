package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaMemoryStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Карта связей памяти: люди по краю, связанные темы и события ближе к ним.
 * Раскладка держит запас под подписи, чтобы текст не наезжал друг на друга.
 */
public class MglaMemoryGraphView extends View {

    public interface NodeClickListener {
        void onNodeClicked(MglaMemoryStorage.MemoryGraph.Node node);
    }

    private static final int LAYOUT_ITERATIONS = 36;
    private static final int MAX_LABEL_CHARS = 18;

    private static class Pos {
        final MglaMemoryStorage.MemoryGraph.Node node;
        float x, y;
        int radius;
        boolean showLabel = true;
        float labelW;
        float labelH;
        ImageReceiver avatar;
        AvatarDrawable avatarDrawable;

        Pos(MglaMemoryStorage.MemoryGraph.Node n) {
            node = n;
        }
    }

    private final ArrayList<Pos> positions = new ArrayList<>();
    private final HashMap<String, Pos> posByKey = new HashMap<>();
    private final ArrayList<MglaMemoryStorage.MemoryGraph.Edge> edges = new ArrayList<>();
    private final HashSet<String> neighborKeys = new HashSet<>();
    private final ArrayList<RectF> labelBoxes = new ArrayList<>();

    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plate = new RectF();
    private final RectF tmpLabel = new RectF();
    private final RectF tmpOther = new RectF();
    private final Path avatarClip = new Path();

    private final ScaleGestureDetector scaler;

    private NodeClickListener listener;
    private int currentAccount = UserConfig.selectedAccount;
    private String selectedKey;
    private float reveal = 1f;
    private ValueAnimator revealAnim;
    private boolean attached;

    private float scale = 1f;
    private float panX;
    private float panY;
    private Pos touched;
    private float downX, downY;
    private float lastX, lastY;
    private boolean moved;
    private boolean panning;

    public MglaMemoryGraphView(Context context) {
        super(context);
        nodePaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scale = Math.max(0.55f, Math.min(2.8f, scale * detector.getScaleFactor()));
                invalidate();
                return true;
            }
        });
    }

    public void setNodeClickListener(NodeClickListener l) {
        listener = l;
    }

    public void setAccount(int account) {
        currentAccount = account;
    }

    public void setGraph(MglaMemoryStorage.MemoryGraph graph) {
        clearAvatars();
        positions.clear();
        posByKey.clear();
        edges.clear();
        neighborKeys.clear();
        touched = null;
        selectedKey = null;
        if (graph != null) {
            for (MglaMemoryStorage.MemoryGraph.Node n : graph.nodes) {
                Pos p = new Pos(n);
                boolean person = "person".equals(n.type);
                p.radius = person ? dp(16) : (dp(8) + Math.min(dp(7), n.weight));
                if (person) {
                    bindPersonAvatar(p);
                }
                positions.add(p);
                posByKey.put(n.key, p);
            }
            edges.addAll(graph.edges);
            layoutGraph();
        }
        playReveal();
        invalidate();
    }

    private void clearAvatars() {
        for (Pos p : positions) {
            if (p.avatar != null) {
                if (attached) {
                    p.avatar.onDetachedFromWindow();
                }
                p.avatar = null;
                p.avatarDrawable = null;
            }
        }
    }

    private void bindPersonAvatar(Pos p) {
        p.avatarDrawable = new AvatarDrawable();
        p.avatar = new ImageReceiver(this);
        p.avatar.setRoundRadius(p.radius);
        if (attached) {
            p.avatar.onAttachedToWindow();
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        String key = p.node.key;
        if ("person:self".equals(key)) {
            TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
            p.avatarDrawable.setInfo(currentAccount, self);
            p.avatar.setForUserOrChat(self, p.avatarDrawable);
            return;
        }
        if (key != null && key.startsWith("person:")) {
            try {
                long peerId = Long.parseLong(key.substring("person:".length()));
                if (peerId > 0) {
                    TLRPC.User user = mc.getUser(peerId);
                    p.avatarDrawable.setInfo(currentAccount, user);
                    p.avatar.setForUserOrChat(user, p.avatarDrawable);
                } else if (peerId < 0) {
                    TLRPC.Chat chat = mc.getChat(-peerId);
                    p.avatarDrawable.setInfo(currentAccount, chat);
                    p.avatar.setForUserOrChat(chat, p.avatarDrawable);
                } else {
                    p.avatarDrawable.setInfo(0, p.node.label != null ? p.node.label : "?", "");
                    p.avatar.setImageBitmap(p.avatarDrawable);
                }
                return;
            } catch (Exception ignore) {
            }
        }
        p.avatarDrawable.setInfo(0, p.node.label != null ? p.node.label : "?", "");
        p.avatar.setImageBitmap(p.avatarDrawable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        for (Pos p : positions) {
            if (p.avatar != null) {
                p.avatar.onAttachedToWindow();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        for (Pos p : positions) {
            if (p.avatar != null) {
                p.avatar.onDetachedFromWindow();
            }
        }
    }

    public void focusLabel(String label) {
        if (label == null || label.length() < 2) {
            return;
        }
        String needle = label.toLowerCase(Locale.ROOT);
        Pos best = null;
        for (Pos p : positions) {
            String text = p.node.label == null ? "" : p.node.label.toLowerCase(Locale.ROOT);
            if (text.contains(needle) || (text.length() >= 3 && needle.contains(text))) {
                best = p;
                break;
            }
        }
        if (best == null) {
            return;
        }
        select(best);
    }

    private void select(Pos pos) {
        selectedKey = pos.node.key;
        neighborKeys.clear();
        neighborKeys.add(selectedKey);
        for (MglaMemoryStorage.MemoryGraph.Edge edge : edges) {
            if (selectedKey.equals(edge.fromKey)) {
                neighborKeys.add(edge.toKey);
            } else if (selectedKey.equals(edge.toKey)) {
                neighborKeys.add(edge.fromKey);
            }
        }
        resolveLabels();
        invalidate();
        if (listener != null) {
            listener.onNodeClicked(pos.node);
        }
    }

    private void playReveal() {
        if (revealAnim != null) {
            revealAnim.cancel();
        }
        reveal = 0f;
        revealAnim = ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(420);
        revealAnim.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        revealAnim.start();
    }

    private boolean isPerson(Pos p) {
        return p != null && "person".equals(p.node.type);
    }

    private void layoutGraph() {
        int n = positions.size();
        if (n == 0) {
            return;
        }
        float w = getWidth() > 0 ? getWidth() : dp(340);
        float h = getHeight() > 0 ? getHeight() : dp(420);
        float cx = w / 2f;
        float cy = h / 2f;

        ArrayList<Pos> people = new ArrayList<>();
        ArrayList<Pos> others = new ArrayList<>();
        for (Pos p : positions) {
            if (isPerson(p)) {
                people.add(p);
            } else {
                others.add(p);
            }
        }

        // Люди — по внешнему кольцу, равномерно.
        float outerR = Math.min(w, h) * 0.40f;
        int peopleCount = Math.max(1, people.size());
        for (int i = 0; i < people.size(); i++) {
            double angle = 2 * Math.PI * i / peopleCount - Math.PI / 2;
            Pos p = people.get(i);
            p.x = cx + (float) Math.cos(angle) * outerR;
            p.y = cy + (float) Math.sin(angle) * outerR * 0.92f;
        }

        // Остальные — ближе к центру / к связанному человеку.
        float innerR = Math.min(w, h) * 0.22f;
        for (int i = 0; i < others.size(); i++) {
            Pos p = others.get(i);
            Pos anchor = findPersonAnchor(p);
            if (anchor != null) {
                float dx = anchor.x - cx;
                float dy = anchor.y - cy;
                float len = (float) Math.sqrt(Math.max(dx * dx + dy * dy, 1f));
                float mid = (outerR + innerR) * 0.52f;
                double jitter = (i % 5 - 2) * 0.18;
                float cos = dx / len;
                float sin = dy / len;
                float px = -sin;
                float py = cos;
                p.x = cx + cos * mid + px * dp(18 + (i % 4) * 10) + (float) Math.cos(jitter) * dp(8);
                p.y = cy + sin * mid * 0.92f + py * dp(18 + (i % 4) * 10);
            } else {
                double angle = 2 * Math.PI * i / Math.max(1, others.size()) + Math.PI / 6;
                p.x = cx + (float) Math.cos(angle) * innerR;
                p.y = cy + (float) Math.sin(angle) * innerR * 0.9f;
            }
        }

        // Минимальная дистанция с запасом под подпись под узлом.
        float minSep = Math.max(dp(86), Math.min(w, h) / Math.max(4.2f, (float) Math.sqrt(n)));
        float spring = Math.max(minSep * 0.92f, dp(78));
        float padX = dp(28);
        float padY = dp(34);

        for (int iter = 0; iter < LAYOUT_ITERATIONS; iter++) {
            float temp = 1f - iter / (float) LAYOUT_ITERATIONS;
            for (int i = 0; i < n; i++) {
                Pos a = positions.get(i);
                for (int j = i + 1; j < n; j++) {
                    Pos b = positions.get(j);
                    float dx = a.x - b.x;
                    float dy = a.y - b.y;
                    float dist = (float) Math.sqrt(Math.max(dx * dx + dy * dy, 1f));
                    if (dist < minSep) {
                        float push = (minSep - dist) * 0.55f * (0.35f + temp);
                        float ux = dx / dist;
                        float uy = dy / dist;
                        // Людей сильнее оставляем снаружи.
                        float aw = isPerson(a) ? 0.35f : 0.65f;
                        float bw = isPerson(b) ? 0.35f : 0.65f;
                        a.x += ux * push * aw;
                        a.y += uy * push * aw;
                        b.x -= ux * push * bw;
                        b.y -= uy * push * bw;
                    } else {
                        float force = Math.min(minSep * minSep / dist, dp(22)) * 0.18f * temp;
                        float ux = dx / dist;
                        float uy = dy / dist;
                        a.x += ux * force;
                        a.y += uy * force;
                        b.x -= ux * force;
                        b.y -= uy * force;
                    }
                }
            }
            for (MglaMemoryStorage.MemoryGraph.Edge e : edges) {
                Pos a = posByKey.get(e.fromKey);
                Pos b = posByKey.get(e.toKey);
                if (a == null || b == null) {
                    continue;
                }
                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float dist = (float) Math.sqrt(Math.max(dx * dx + dy * dy, 1f));
                float force = (dist - spring) * 0.028f * temp;
                float ux = dx / dist;
                float uy = dy / dist;
                a.x += ux * force;
                a.y += uy * force;
                b.x -= ux * force;
                b.y -= uy * force;
            }
            for (Pos p : positions) {
                if (isPerson(p)) {
                    // Мягко держим людей на внешнем кольце.
                    float dx = p.x - cx;
                    float dy = p.y - cy;
                    float dist = (float) Math.sqrt(Math.max(dx * dx + dy * dy, 1f));
                    float target = outerR;
                    p.x += dx / dist * (target - dist) * 0.08f * temp;
                    p.y += dy / dist * (target - dist) * 0.08f * temp;
                } else {
                    p.x += (cx - p.x) * 0.012f * temp;
                    p.y += (cy - p.y) * 0.012f * temp;
                }
                p.x = Math.max(padX + p.radius, Math.min(w - padX - p.radius, p.x));
                p.y = Math.max(padY + p.radius, Math.min(h - padY - p.radius - dp(16), p.y));
            }
        }

        // Финальный проход: раздвинуть всё, что всё ещё ближе минимума.
        for (int pass = 0; pass < 18; pass++) {
            boolean movedAny = false;
            for (int i = 0; i < n; i++) {
                Pos a = positions.get(i);
                for (int j = i + 1; j < n; j++) {
                    Pos b = positions.get(j);
                    float dx = a.x - b.x;
                    float dy = a.y - b.y;
                    float dist = (float) Math.sqrt(Math.max(dx * dx + dy * dy, 1f));
                    if (dist >= minSep) {
                        continue;
                    }
                    movedAny = true;
                    float push = (minSep - dist) * 0.5f;
                    float ux = dx / dist;
                    float uy = dy / dist;
                    a.x += ux * push;
                    a.y += uy * push;
                    b.x -= ux * push;
                    b.y -= uy * push;
                    a.x = Math.max(padX + a.radius, Math.min(w - padX - a.radius, a.x));
                    a.y = Math.max(padY + a.radius, Math.min(h - padY - a.radius - dp(16), a.y));
                    b.x = Math.max(padX + b.radius, Math.min(w - padX - b.radius, b.x));
                    b.y = Math.max(padY + b.radius, Math.min(h - padY - b.radius - dp(16), b.y));
                }
            }
            if (!movedAny) {
                break;
            }
        }
        resolveLabels();
    }

    private Pos findPersonAnchor(Pos node) {
        Pos best = null;
        int bestWeight = -1;
        for (MglaMemoryStorage.MemoryGraph.Edge e : edges) {
            Pos other = null;
            if (node.node.key.equals(e.fromKey)) {
                other = posByKey.get(e.toKey);
            } else if (node.node.key.equals(e.toKey)) {
                other = posByKey.get(e.fromKey);
            }
            if (other == null || !isPerson(other)) {
                continue;
            }
            if (e.weight > bestWeight) {
                bestWeight = e.weight;
                best = other;
            }
        }
        return best;
    }

    private String shortLabel(Pos p) {
        String label = p.node.label == null ? "" : p.node.label.replace('\n', ' ').trim();
        if (label.length() > MAX_LABEL_CHARS) {
            label = label.substring(0, MAX_LABEL_CHARS - 1) + "…";
        }
        return label;
    }

    private void measureLabel(Pos p) {
        String label = shortLabel(p);
        labelPaint.setTextSize(dp(11));
        p.labelW = Math.max(dp(24), labelPaint.measureText(label) + dp(8));
        p.labelH = dp(16);
    }

    private void labelRect(Pos p, RectF out) {
        float top = p.y + p.radius + dp(4);
        out.set(p.x - p.labelW / 2f, top, p.x + p.labelW / 2f, top + p.labelH);
    }

    /** Оставляет подписи без пересечений: сначала люди и выбранный узел. */
    private void resolveLabels() {
        labelBoxes.clear();
        ArrayList<Pos> order = new ArrayList<>(positions);
        order.sort((a, b) -> {
            int as = scoreLabel(a);
            int bs = scoreLabel(b);
            if (as != bs) {
                return Integer.compare(bs, as);
            }
            return Integer.compare(b.node.weight, a.node.weight);
        });
        ArrayList<Pos> kept = new ArrayList<>();
        for (Pos p : positions) {
            p.showLabel = false;
        }
        for (Pos p : order) {
            measureLabel(p);
            labelRect(p, tmpLabel);
            boolean collide = false;
            for (Pos other : kept) {
                labelRect(other, tmpOther);
                if (RectF.intersects(tmpOther, tmpLabel)) {
                    collide = true;
                    break;
                }
            }
            if (collide) {
                continue;
            }
            p.showLabel = true;
            kept.add(p);
            labelBoxes.add(new RectF(tmpLabel));
        }
    }

    private int scoreLabel(Pos p) {
        if (p.node.key.equals(selectedKey)) {
            return 100;
        }
        if (neighborKeys.contains(p.node.key)) {
            return 80;
        }
        if (isPerson(p)) {
            return 60;
        }
        if ("event".equals(p.node.type)) {
            return 40;
        }
        return 20 + Math.min(20, p.node.weight);
    }

    private int accent() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private int plateColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    private int labelColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int edgeColor() {
        return Theme.getColor(Theme.key_divider);
    }

    private int colorForType(String type) {
        // Люди/группы — красные, темы и прочие пункты — голубые.
        if ("person".equals(type)) {
            return 0xFFE53935;
        }
        if ("file".equals(type)) {
            return 0xFF29B6F6;
        }
        if ("event".equals(type)) {
            return 0xFF039BE5;
        }
        return 0xFF42A5F5;
    }

    private boolean related(String key) {
        return selectedKey == null || neighborKeys.contains(key);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        platePaint.setColor(plateColor());
        plate.set(dp(2), dp(2), getWidth() - dp(2), getHeight() - dp(2));
        canvas.drawRoundRect(plate, dp(18), dp(18), platePaint);

        if (positions.isEmpty()) {
            labelPaint.setColor(labelColor());
            labelPaint.setAlpha(150);
            canvas.drawText("Связей пока нет", getWidth() / 2f, getHeight() / 2f - dp(8), labelPaint);
            canvas.drawText("Они появятся из записей памяти", getWidth() / 2f, getHeight() / 2f + dp(12), labelPaint);
            labelPaint.setAlpha(255);
            return;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.save();
        canvas.translate(panX, panY);
        canvas.scale(scale, scale, cx, cy);

        int accent = accent();
        for (MglaMemoryStorage.MemoryGraph.Edge e : edges) {
            Pos a = posByKey.get(e.fromKey);
            Pos b = posByKey.get(e.toKey);
            if (a == null || b == null) {
                continue;
            }
            boolean hot = selectedKey != null && (selectedKey.equals(e.fromKey) || selectedKey.equals(e.toKey));
            edgePaint.setColor(hot ? accent : edgeColor());
            int alpha = hot ? 220 : 110;
            if (selectedKey != null && !hot) {
                alpha = 32;
            }
            edgePaint.setAlpha((int) (alpha * reveal));
            edgePaint.setStrokeWidth((hot ? dp(1.6f) : dp(1f)) / scale);
            canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint);
        }

        for (Pos p : positions) {
            boolean selected = p.node.key.equals(selectedKey);
            boolean near = related(p.node.key);
            int alpha = (int) ((near ? 255 : 48) * reveal);
            float r = p.radius * (selected ? 1.3f : 1f);
            if (p.avatar != null) {
                p.avatar.setAlpha(alpha / 255f);
                p.avatar.setImageCoords(p.x - r, p.y - r, r * 2f, r * 2f);
                p.avatar.setRoundRadius((int) r);
                canvas.save();
                avatarClip.reset();
                avatarClip.addCircle(p.x, p.y, r, Path.Direction.CW);
                canvas.clipPath(avatarClip);
                p.avatar.draw(canvas);
                canvas.restore();
            } else {
                nodePaint.setColor(selected ? accent : colorForType(p.node.type));
                nodePaint.setAlpha(alpha);
                canvas.drawCircle(p.x, p.y, r, nodePaint);
            }
            ringPaint.setColor(selected ? accent : Theme.getColor(Theme.key_windowBackgroundWhite));
            ringPaint.setAlpha(alpha);
            ringPaint.setStrokeWidth((selected ? dp(1.8f) : dp(1.1f)) / scale);
            canvas.drawCircle(p.x, p.y, r, ringPaint);

            if ((!near && selectedKey != null) || !p.showLabel || reveal < 0.35f) {
                continue;
            }
            String label = shortLabel(p);
            labelPaint.setColor(labelColor());
            labelPaint.setAlpha((int) (230 * reveal));
            labelPaint.setTextSize(dp(selected ? 12 : 11) / scale);
            canvas.drawText(label, p.x, p.y + r + dp(14) / scale, labelPaint);
        }
        canvas.restore();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY || h <= 0) {
            h = dp(420);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (oldw == 0 || Math.abs(w - oldw) > dp(24) || Math.abs(h - oldh) > dp(24))) {
            layoutGraph();
            invalidate();
        }
    }

    private void toLocal(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        localX = (x - panX - cx) / scale + cx;
        localY = (y - panY - cy) / scale + cy;
    }

    private float localX, localY;

    private Pos findNode(float x, float y) {
        toLocal(x, y);
        for (int i = positions.size() - 1; i >= 0; i--) {
            Pos p = positions.get(i);
            float dx = localX - p.x;
            float dy = localY - p.y;
            float hit = p.radius + dp(14);
            if (dx * dx + dy * dy <= hit * hit) {
                return p;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        if (event.getPointerCount() > 1 || scaler.isInProgress()) {
            touched = null;
            panning = false;
            return true;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                touched = findNode(x, y);
                panning = touched == null;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - downX;
                float dy = y - downY;
                if (dx * dx + dy * dy > dp(8) * dp(8)) {
                    moved = true;
                }
                if (touched != null) {
                    toLocal(x, y);
                    touched.x = localX;
                    touched.y = localY;
                    resolveLabels();
                    invalidate();
                } else if (panning) {
                    panX += x - lastX;
                    panY += y - lastY;
                    invalidate();
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (touched != null && !moved) {
                    select(touched);
                } else if (touched != null) {
                    resolveLabels();
                    invalidate();
                }
                touched = null;
                panning = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                touched = null;
                panning = false;
                break;
        }
        return super.onTouchEvent(event);
    }
}
