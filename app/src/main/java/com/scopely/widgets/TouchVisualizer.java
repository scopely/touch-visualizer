package com.scopely.widgets;

import java.util.Stack;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Layout that allows visualization of touches...
 */
public class TouchVisualizer extends FrameLayout {
    private static final String TAG = TouchVisualizer.class.getSimpleName();

    /**
     * How this works:
     * TouchVisualizer intercepts all touch events but does not handle them. It then acquires a
     * touch view from a pool of views and uses it to visualize the touch, placing the view where
     * the touch occurred.
     *
     * There is an alternative to this which is drawing the touches directly in the
     * {@link android.view.ViewGroup#dispatchDraw(android.graphics.Canvas)} call. However this solution
     * does not work if the children to this layout is a GLSurfaceView. It appears that the
     * GLSurfaceView simply draws itself over this view group but not on top of it's children which
     * is why TouchVisualizer is implemented in this way specifically.
     */

    /**
     * Minimum amount of time to show a touch event. This prevents quick touches from being missed
     * especially in recordings
     */
    private static final int MIN_LIFE_TIME_MS = 300;

    /**
     * Capacity of the touch view pool
     */
    private static final int INITIAL_VIEW_POOL_SIZE = 10;

    /**
     * Default size for touch views
     */
    private static final int DEFAULT_TOUCH_CIRCLE_RADIUS_DP = 10;

    /**
     * Default color of touch views
     */
    private static final int DEFAULT_TOUCH_COLOR = Color.RED;

    private float touchViewRadius;

    /**
     * Pointer id to touch view map
     */
    private SparseArray<TouchView> pidToTouchViewMap;

    /**
     * Stack used to hold unused touch views so that they may be recycled
     */
    private Stack<TouchView> touchViewPool;

    private Paint paint;

    public TouchVisualizer(Context context) {
        super(context);
        init();
    }

    public TouchVisualizer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TouchVisualizer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(DEFAULT_TOUCH_COLOR);

        pidToTouchViewMap = new SparseArray<TouchView>();
        touchViewPool = new Stack<TouchView>();

        touchViewRadius = convertDpToPixel(DEFAULT_TOUCH_CIRCLE_RADIUS_DP, getContext());

        for (int i = 0; i < INITIAL_VIEW_POOL_SIZE; i++) {
            TouchView v = new TouchView(getContext());
            v.setLayoutParams(new FrameLayout.LayoutParams((int) (touchViewRadius * 2), (int) (touchViewRadius * 2)));
            v.setVisibility(View.GONE);
            addView(v);

            touchViewPool.add(v);
        }
    }

    private TouchView getTouchView() {
        TouchView v;
        if (touchViewPool.size() == 0) {
            v = new TouchView(getContext());
            v.setLayoutParams(new FrameLayout.LayoutParams((int) (touchViewRadius * 2), (int) (touchViewRadius * 2)));
            v.setVisibility(View.GONE);
            addView(v);
        } else {
            v = touchViewPool.pop();
        }

        return v;
    }

    private void releaseTouchView(TouchView view) {
        touchViewPool.add(view);
    }

    /**
     * Set's the color of the touch visualizer
     * @param color Color to use
     */
    public void setTouchColor(int color) {
        paint.setColor(color);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        handleTouch(event);
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // If no view handled the touch
        if (!super.onTouchEvent(event)) {

            // Time for us to handle it
            handleTouch(event);
        }
        return true;
    }

    private void handleTouch(MotionEvent event) {
        int action = event.getActionMasked();

        int pointerIndex = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) ? 0 : event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                synchronized (pidToTouchViewMap) {
                    TouchView tv = pidToTouchViewMap.get(pointerId, null);
                    if (tv == null) {
                        tv = getTouchView();
                        tv.setVisibility(View.VISIBLE);
                        tv.bringToFront();
                        tv.pointerId = pointerId;

                        pidToTouchViewMap.put(pointerId, tv);
                    }
                    tv.dead = false;
                    tv.minDeathTime = System.currentTimeMillis() + MIN_LIFE_TIME_MS;

                    tv.moveTo(event.getX(pointerIndex), event.getY(pointerIndex));
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                final int pointerCount = event.getPointerCount();
                for (int i = 0; i < pointerCount; i++) {
                    int curPointerId = event.getPointerId(i);
                    TouchView tv = pidToTouchViewMap.get(curPointerId, null);
                    if (tv != null) {
                        tv.moveTo(event.getX(i), event.getY(i));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                TouchView tv = pidToTouchViewMap.get(pointerId);
                if (tv == null) {
                } else if (tv.minDeathTime < System.currentTimeMillis()) {
                    removeTouch(tv);
                } else {
                    tv.dead = true;

                    final int key = tv.pointerId;
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (pidToTouchViewMap) {
                                TouchView tv = pidToTouchViewMap.get(key, null);
                                if (tv != null && tv.dead) {
                                    removeTouch(tv);
                                }
                            }
                        }
                    }, tv.minDeathTime - System.currentTimeMillis());
                }
                break;
            case MotionEvent.ACTION_OUTSIDE:
                break;
        }
        invalidate();
    }

    private void removeTouch(TouchView tv) {
        pidToTouchViewMap.remove(tv.pointerId);
        tv.setVisibility(View.GONE);
        releaseTouchView(tv);
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device density.
     *
     * @param dp A value in dp (density independent pixels) unit. Which we need to convert into pixels
     * @param context Context to get resources and device specific display metrics
     * @return A float value to represent px equivalent to dp depending on device density
     */
    public static float convertDpToPixel(float dp, Context context){
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        float px = dp * (metrics.densityDpi / 160f);
        return px;
    }

    private class TouchView extends View {
        boolean dead;
        long minDeathTime;
        int pointerId;

        public TouchView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas c) {
            final int w = getWidth();
            final int h = getHeight();
            c.drawCircle(w / 2, h / 2, w / 2, paint);
        }

        private void moveTo(float x, float y) {
            FrameLayout.LayoutParams lp = (LayoutParams) getLayoutParams();
            lp.leftMargin = (int) (x - touchViewRadius);
            lp.topMargin = (int) (y - touchViewRadius);
            requestLayout();
        }

    }
}
