package com.android.contacts.widget;

import com.android.contacts.R;
import com.android.contacts.test.NeededForReflection;
import com.android.contacts.util.SchedulingUtils;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.EdgeEffect;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Scroller;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A custom {@link ViewGroup} that operates similarly to a {@link ScrollView}, except with multiple
 * subviews. These subviews are scrolled or shrinked one at a time, until each reaches their
 * minimum or maximum value.
 *
 * MultiShrinkScroller is designed for a specific problem. As such, this class is designed to be
 * used with a specific layout file: quickcontact_activity.xml. MultiShrinkScroller expects subviews
 * with specific ID values.
 *
 * MultiShrinkScroller's code is heavily influenced by ScrollView. Nonetheless, several ScrollView
 * features are missing. For example: handling of KEYCODES, OverScroll bounce and saving
 * scroll state in savedInstanceState bundles.
 */
public class MultiShrinkScroller extends LinearLayout {

    /**
     * 1000 pixels per millisecond. Ie, 1 pixel per second.
     */
    private static final int PIXELS_PER_SECOND = 1000;

    /**
     * Length of the acceleration animations. This value was taken from ValueAnimator.java.
     */
    private static final int EXIT_FLING_ANIMATION_DURATION_MS = 300;

    /**
     * In portrait mode, the height:width ratio of the photo's starting height.
     */
    private static final float INTERMEDIATE_HEADER_HEIGHT_RATIO = 0.5f;

    private float[] mLastEventPosition = { 0, 0 };
    private VelocityTracker mVelocityTracker;
    private boolean mIsBeingDragged = false;
    private boolean mReceivedDown = false;

    private ScrollView mScrollView;
    private View mScrollViewChild;
    private View mToolbar;
    private QuickContactImageView mPhotoView;
    private View mPhotoViewContainer;
    private View mTransparentView;
    private MultiShrinkScrollerListener mListener;
    private TextView mLargeTextView;
    /** Contains desired location/size of the title, once the header is fully compressed */
    private TextView mInvisiblePlaceholderTextView;
    private int mHeaderTintColor;
    private int mMaximumHeaderHeight;
    private int mMinimumHeaderHeight;
    private int mIntermediateHeaderHeight;
    private int mMaximumHeaderTextSize;
    private int mCollapsedTitleBottomMargin;
    private int mCollapsedTitleStartMargin;

    private final Scroller mScroller;
    private final EdgeEffect mEdgeGlowBottom;
    private final int mTouchSlop;
    private final int mMaximumVelocity;
    private final int mMinimumVelocity;
    private final int mTransparentStartHeight;
    private final int mMaximumTitleMargin;
    private final float mToolbarElevation;
    private final boolean mIsTwoPanel;

    // Objects used to perform color filtering on the header. These are stored as fields for
    // the sole purpose of avoiding "new" operations inside animation loops.
    private final ColorMatrix mWhitenessColorMatrix = new ColorMatrix();
    private final  ColorMatrixColorFilter mColorFilter = new ColorMatrixColorFilter(
            mWhitenessColorMatrix);
    private final ColorMatrix mColorMatrix = new ColorMatrix();
    private final float[] mAlphaMatrixValues = {
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0
    };
    private final ColorMatrix mMultiplyBlendMatrix = new ColorMatrix();
    private final float[] mMultiplyBlendMatrixValues = {
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0
    };

    public interface MultiShrinkScrollerListener {
        void onScrolledOffBottom();

        void onStartScrollOffBottom();

        void onEnterFullscreen();

        void onExitFullscreen();
    }

    private final AnimatorListener mHeaderExpandAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mPhotoView.setClickable(true);
        }
    };

    private final AnimatorListener mSnapToBottomListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (getScrollUntilOffBottom() > 0 && mListener != null) {
                // Due to a rounding error, after the animation finished we haven't fully scrolled
                // off the screen. Lie to the listener: tell it that we did scroll off the screen.
                mListener.onScrolledOffBottom();
                // No other messages need to be sent to the listener.
                mListener = null;
            }
        }
    };

    /**
     * Interpolator from android.support.v4.view.ViewPager. Snappier and more elastic feeling
     * than the default interpolator.
     */
    private static final Interpolator sInterpolator = new Interpolator() {

        /**
         * {@inheritDoc}
         */
        @Override
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    public MultiShrinkScroller(Context context) {
        this(context, null);
    }

    public MultiShrinkScroller(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiShrinkScroller(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final ViewConfiguration configuration = ViewConfiguration.get(context);
        setFocusable(false);
        // Drawing must be enabled in order to support EdgeEffect
        setWillNotDraw(/* willNotDraw = */ false);

        mEdgeGlowBottom = new EdgeEffect(context);
        mScroller = new Scroller(context, sInterpolator);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mTransparentStartHeight = (int) getResources().getDimension(
                R.dimen.quickcontact_starting_empty_height);
        mToolbarElevation = getResources().getDimension(
                R.dimen.quick_contact_toolbar_elevation);
        mIsTwoPanel = getResources().getBoolean(R.bool.quickcontact_two_panel);
        mMaximumTitleMargin = (int) getResources().getDimension(
                R.dimen.quickcontact_title_initial_margin);

        final TypedArray attributeArray = context.obtainStyledAttributes(
                new int[]{android.R.attr.actionBarSize});
        mMinimumHeaderHeight = attributeArray.getDimensionPixelSize(0, 0);
        attributeArray.recycle();
    }

    /**
     * This method must be called inside the Activity's OnCreate.
     */
    public void initialize(MultiShrinkScrollerListener listener) {
        mScrollView = (ScrollView) findViewById(R.id.content_scroller);
        mScrollViewChild = findViewById(R.id.card_container);
        mToolbar = findViewById(R.id.toolbar_parent);
        mPhotoViewContainer = findViewById(R.id.toolbar_parent);
        mTransparentView = findViewById(R.id.transparent_view);
        mLargeTextView = (TextView) findViewById(R.id.large_title);
        mInvisiblePlaceholderTextView = (TextView) findViewById(R.id.placeholder_textview);
        mListener = listener;

        mPhotoView = (QuickContactImageView) findViewById(R.id.photo);
        mPhotoView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                expandCollapseHeader();
            }
        });

        final WindowManager windowManager = (WindowManager) getContext().getSystemService(
                Context.WINDOW_SERVICE);
        final Point windowSize = new Point();
        windowManager.getDefaultDisplay().getSize(windowSize);
        if (!mIsTwoPanel) {
            // We never want the height of the photo view to exceed its width.
            mMaximumHeaderHeight = windowSize.x;
            mIntermediateHeaderHeight = (int) (mMaximumHeaderHeight
                    * INTERMEDIATE_HEADER_HEIGHT_RATIO);
        }
        setHeaderHeight(mIntermediateHeaderHeight);

        SchedulingUtils.doOnPreDraw(this, /* drawNextFrame = */ false, new Runnable() {
            @Override
            public void run() {
                mMaximumHeaderTextSize = mLargeTextView.getHeight();
                // Unlike Window width, we can't know the usable window height until predraw
                // has occured. Therefore, setting these constraints must be done inside
                // onPreDraw for the two panel layout. Fortunately, the two panel layout
                // doesn't need these values anywhere else inside the activity's creation.
                if (mIsTwoPanel) {
                    mMaximumHeaderHeight = getHeight();
                    mMinimumHeaderHeight = mMaximumHeaderHeight;
                    mIntermediateHeaderHeight = mMaximumHeaderHeight;
                    final TypedValue photoRatio = new TypedValue();
                    getResources().getValue(R.vals.quickcontact_photo_ratio, photoRatio,
                            /* resolveRefs = */ true);
                    final LayoutParams layoutParams
                            = (LayoutParams) mPhotoViewContainer.getLayoutParams();
                    layoutParams.height = mMaximumHeaderHeight;
                    layoutParams.width = (int) (mMaximumHeaderHeight * photoRatio.getFloat());
                    mPhotoViewContainer.setLayoutParams(layoutParams);
                }

                calculateCollapsedLargeTitlePadding();
                updateHeaderTextSize();
            }
        });
    }

    public void setTitle(String title) {
        mLargeTextView.setText(title);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // The only time we want to intercept touch events is when we are being dragged.
        return shouldStartDrag(event);
    }

    private boolean shouldStartDrag(MotionEvent event) {
        if (mIsBeingDragged) {
            mIsBeingDragged = false;
            return false;
        }

        switch (event.getAction()) {
            // If we are in the middle of a fling and there is a down event, we'll steal it and
            // start a drag.
            case MotionEvent.ACTION_DOWN:
                updateLastEventPosition(event);
                if (!mScroller.isFinished()) {
                    startDrag();
                    return true;
                } else {
                    mReceivedDown = true;
                }
                break;

            // Otherwise, we will start a drag if there is enough motion in the direction we are
            // capable of scrolling.
            case MotionEvent.ACTION_MOVE:
                if (motionShouldStartDrag(event)) {
                    updateLastEventPosition(event);
                    startDrag();
                    return true;
                }
                break;
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        if (!mIsBeingDragged) {
            if (shouldStartDrag(event)) {
                return true;
            }

            if (action == MotionEvent.ACTION_UP && mReceivedDown) {
                mReceivedDown = false;
                return performClick();
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                final float delta = updatePositionAndComputeDelta(event);
                scrollTo(0, getScroll() + (int) delta);
                mReceivedDown = false;

                if (mIsBeingDragged) {
                    final int distanceFromMaxScrolling = getMaximumScrollUpwards() - getScroll();
                    if (delta > distanceFromMaxScrolling) {
                        // The ScrollView is being pulled upwards while there is no more
                        // content offscreen, and the view port is already fully expanded.
                        mEdgeGlowBottom.onPull(delta / getHeight(), 1 - event.getX() / getWidth());
                    }

                    if (!mEdgeGlowBottom.isFinished()) {
                        postInvalidateOnAnimation();
                    }

                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopDrag(action == MotionEvent.ACTION_CANCEL);
                mReceivedDown = false;
                break;
        }

        return true;
    }

    public void setHeaderTintColor(int color) {
        mHeaderTintColor = color;
        updatePhotoTintAndDropShadow();
        // We want to use the same amount of alpha on the new tint color as the previous tint color.
        final int edgeEffectAlpha = Color.alpha(mEdgeGlowBottom.getColor());
        mEdgeGlowBottom.setColor((color & 0xffffff) | Color.argb(edgeEffectAlpha, 0, 0, 0));
    }

    /**
     * Expand to maximum size or starting size. Disable clicks on the photo until the animation is
     * complete.
     */
    private void expandCollapseHeader() {
        mPhotoView.setClickable(false);
        if (getHeaderHeight() != mMaximumHeaderHeight) {
            // Expand header
            final ObjectAnimator animator = ObjectAnimator.ofInt(this, "headerHeight",
                    mMaximumHeaderHeight);
            animator.addListener(mHeaderExpandAnimationListener);
            animator.start();
            // Scroll nested scroll view to its top
            if (mScrollView.getScrollY() != 0) {
                ObjectAnimator.ofInt(mScrollView, "scrollY", -mScrollView.getScrollY()).start();
            }
        } else if (getHeaderHeight() != mMinimumHeaderHeight) {
            final ObjectAnimator animator = ObjectAnimator.ofInt(this, "headerHeight",
                    mIntermediateHeaderHeight);
            animator.addListener(mHeaderExpandAnimationListener);
            animator.start();
        }
    }

    private void startDrag() {
        mIsBeingDragged = true;
        mScroller.abortAnimation();
    }

    private void stopDrag(boolean cancelled) {
        mIsBeingDragged = false;
        if (!cancelled && getChildCount() > 0) {
            final float velocity = getCurrentVelocity();
            if (velocity > mMinimumVelocity || velocity < -mMinimumVelocity) {
                fling(-velocity);
                onDragFinished(mScroller.getFinalY() - mScroller.getStartY());
            } else {
                onDragFinished(/* flingDelta = */ 0);
            }
        } else {
            onDragFinished(/* flingDelta = */ 0);
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        mEdgeGlowBottom.onRelease();
    }

    private void onDragFinished(int flingDelta) {
        if (!snapToTop(flingDelta)) {
            // The drag/fling won't result in the content at the top of the Window. Consider
            // snapping the content to the bottom of the window.
            snapToBottom(flingDelta);
        }
    }

    /**
     * If needed, snap the subviews to the top of the Window.
     */
    private boolean snapToTop(int flingDelta) {
        final int requiredScroll = -getScroll_ignoreOversizedHeader() + mTransparentStartHeight;
        if (-getScroll_ignoreOversizedHeader() - flingDelta < 0
                && -getScroll_ignoreOversizedHeader() - flingDelta > -mTransparentStartHeight
                && requiredScroll != 0) {
            // We finish scrolling above the empty starting height, and aren't projected
            // to fling past the top of the Window, so elastically snap the empty space shut.
            mScroller.forceFinished(true);
            smoothScrollBy(requiredScroll);
            return true;
        }
        return false;
    }

    /**
     * If needed, scroll all the subviews off the bottom of the Window.
     */
    private void snapToBottom(int flingDelta) {
        if (-getScroll_ignoreOversizedHeader() - flingDelta > 0) {
            scrollOffBottom();
        }
    }

    public void scrollOffBottom() {
        final Interpolator interpolator = new AcceleratingFlingInterpolator(
                EXIT_FLING_ANIMATION_DURATION_MS, getCurrentVelocity(),
                getScrollUntilOffBottom());
        mScroller.forceFinished(true);
        ObjectAnimator translateAnimation = ObjectAnimator.ofInt(this, "scroll",
                getScroll() - getScrollUntilOffBottom());
        translateAnimation.setRepeatCount(0);
        translateAnimation.setInterpolator(interpolator);
        translateAnimation.setDuration(EXIT_FLING_ANIMATION_DURATION_MS);
        translateAnimation.addListener(mSnapToBottomListener);
        translateAnimation.start();
        if (mListener != null) {
            mListener.onStartScrollOffBottom();
        }
    }

    @Override
    public void scrollTo(int x, int y) {
        final int delta = y - getScroll();
        boolean wasFullscreen = getScrollNeededToBeFullScreen() <= 0;
        if (delta > 0) {
            scrollUp(delta);
        } else {
            scrollDown(delta);
        }
        updatePhotoTintAndDropShadow();
        updateHeaderTextSize();
        final boolean isFullscreen = getScrollNeededToBeFullScreen() <= 0;
        if (mListener != null) {
            if (wasFullscreen && !isFullscreen) {
                 mListener.onExitFullscreen();
            } else if (!wasFullscreen && isFullscreen) {
                mListener.onEnterFullscreen();
            }
        }
    }

    /**
     * Set the height of the toolbar and update its tint accordingly.
     */
    @NeededForReflection
    public void setHeaderHeight(int height) {
        final LinearLayout.LayoutParams toolbarLayoutParams
                = (LayoutParams) mToolbar.getLayoutParams();
        toolbarLayoutParams.height = height;
        mToolbar.setLayoutParams(toolbarLayoutParams);
        updatePhotoTintAndDropShadow();
        updateHeaderTextSize();
    }

    @NeededForReflection
    public int getHeaderHeight() {
        return mToolbar.getLayoutParams().height;
    }

    @NeededForReflection
    public void setScroll(int scroll) {
        scrollTo(0, scroll);
    }

    /**
     * Returns the total amount scrolled inside the nested ScrollView + the amount of shrinking
     * performed on the ToolBar. This is the value inspected by animators.
     */
    @NeededForReflection
    public int getScroll() {
        final LinearLayout.LayoutParams toolbarLayoutParams
                = (LayoutParams) mToolbar.getLayoutParams();
        return mTransparentStartHeight - getTransparentViewHeight()
                + mIntermediateHeaderHeight - toolbarLayoutParams.height + mScrollView.getScrollY();
    }

    /**
     * A variant of {@link #getScroll} that pretends the header is never larger than
     * than mIntermediateHeaderHeight. This function is sometimes needed when making scrolling
     * decisions that will not change the header size (ie, snapping to the bottom or top).
     */
    public int getScroll_ignoreOversizedHeader() {
        final LinearLayout.LayoutParams toolbarLayoutParams
                = (LayoutParams) mToolbar.getLayoutParams();
        return mTransparentStartHeight - getTransparentViewHeight()
                + Math.max(mIntermediateHeaderHeight - toolbarLayoutParams.height, 0)
                + mScrollView.getScrollY();
    }

    /**
     * Amount of transparent space above the header/toolbar.
     */
    public int getScrollNeededToBeFullScreen() {
        return getTransparentViewHeight();
    }

    /**
     * Return amount of scrolling needed in order for all the visible subviews to scroll off the
     * bottom.
     */
    public int getScrollUntilOffBottom() {
        return getHeight() + getScroll_ignoreOversizedHeader() - mTransparentStartHeight;
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            // Examine the fling results in order to activate EdgeEffect when we fling to the end.
            final int oldScroll = getScroll();
            scrollTo(0, mScroller.getCurrY());
            final int delta = mScroller.getCurrY() - oldScroll;
            final int distanceFromMaxScrolling = getMaximumScrollUpwards() - getScroll();
            if (delta > distanceFromMaxScrolling && distanceFromMaxScrolling > 0) {
                mEdgeGlowBottom.onAbsorb((int) mScroller.getCurrVelocity());
            }

            if (!awakenScrollBars()) {
                // Keep on drawing until the animation has finished.
                postInvalidateOnAnimation();
            }
            if (mScroller.getCurrY() >= getMaximumScrollUpwards()) {
                mScroller.abortAnimation();
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (!mEdgeGlowBottom.isFinished()) {
            final int restoreCount = canvas.save();
            final int width = getWidth() - getPaddingLeft() - getPaddingRight();
            final int height = getHeight();

            // Draw the EdgeEffect on the bottom of the Window (Or a little bit below the bottom
            // of the Window if we start to scroll upwards while EdgeEffect is visible). This
            // does not need to consider the case where this MultiShrinkScroller doesn't fill
            // the Window, since the nested ScrollView should be set to fillViewport.
            canvas.translate(-width + getPaddingLeft(),
                    height + getMaximumScrollUpwards() - getScroll());

            canvas.rotate(180, width, 0);
            if (mIsTwoPanel) {
                // Only show the EdgeEffect on the bottom of the ScrollView.
                mEdgeGlowBottom.setSize(mScrollView.getWidth(), height);
            } else {
                mEdgeGlowBottom.setSize(width, height);
            }
            if (mEdgeGlowBottom.draw(canvas)) {
                postInvalidateOnAnimation();
            }
            canvas.restoreToCount(restoreCount);
        }
    }

    private float getCurrentVelocity() {
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(PIXELS_PER_SECOND, mMaximumVelocity);
        return mVelocityTracker.getYVelocity();
    }

    private void fling(float velocity) {
        // For reasons I do not understand, scrolling is less janky when maxY=Integer.MAX_VALUE
        // then when maxY is set to an actual value.
        mScroller.fling(0, getScroll(), 0, (int) velocity, 0, 0, -Integer.MAX_VALUE,
                Integer.MAX_VALUE);
        invalidate();
    }

    private int getMaximumScrollUpwards() {
        if (!mIsTwoPanel) {
            return mTransparentStartHeight
                    // How much the Header view can compress
                    + mIntermediateHeaderHeight - mMinimumHeaderHeight
                    // How much the ScrollView can scroll. 0, if child is smaller than ScrollView.
                    + Math.max(0, mScrollViewChild.getHeight() - getHeight()
                    + mMinimumHeaderHeight);
        } else {
            return mTransparentStartHeight
                    // How much the ScrollView can scroll. 0, if child is smaller than ScrollView.
                    + Math.max(0, mScrollViewChild.getHeight() - getHeight());
        }
    }

    private int getTransparentViewHeight() {
        return mTransparentView.getLayoutParams().height;
    }

    private void setTransparentViewHeight(int height) {
        mTransparentView.getLayoutParams().height = height;
        mTransparentView.setLayoutParams(mTransparentView.getLayoutParams());
    }

    private void scrollUp(int delta) {
        if (getTransparentViewHeight() != 0) {
            final int originalValue = getTransparentViewHeight();
            setTransparentViewHeight(getTransparentViewHeight() - delta);
            setTransparentViewHeight(Math.max(0, getTransparentViewHeight()));
            delta -= originalValue - getTransparentViewHeight();
        }
        final LinearLayout.LayoutParams toolbarLayoutParams
                = (LayoutParams) mToolbar.getLayoutParams();
        if (toolbarLayoutParams.height != mMinimumHeaderHeight) {
            final int originalValue = toolbarLayoutParams.height;
            toolbarLayoutParams.height -= delta;
            toolbarLayoutParams.height = Math.max(toolbarLayoutParams.height, mMinimumHeaderHeight);
            mToolbar.setLayoutParams(toolbarLayoutParams);
            delta -= originalValue - toolbarLayoutParams.height;
        }
        mScrollView.scrollBy(0, delta);
    }

    private void scrollDown(int delta) {
        if (mScrollView.getScrollY() > 0) {
            final int originalValue = mScrollView.getScrollY();
            mScrollView.scrollBy(0, delta);
            delta -= mScrollView.getScrollY() - originalValue;
        }
        final LinearLayout.LayoutParams toolbarLayoutParams
                = (LayoutParams) mToolbar.getLayoutParams();
        if (toolbarLayoutParams.height < mIntermediateHeaderHeight) {
            final int originalValue = toolbarLayoutParams.height;
            toolbarLayoutParams.height -= delta;
            toolbarLayoutParams.height = Math.min(toolbarLayoutParams.height,
                    mIntermediateHeaderHeight);
            mToolbar.setLayoutParams(toolbarLayoutParams);
            delta -= originalValue - toolbarLayoutParams.height;
        }
        setTransparentViewHeight(getTransparentViewHeight() - delta);

        if (getScrollUntilOffBottom() <= 0) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (mListener != null) {
                        mListener.onScrolledOffBottom();
                        // No other messages need to be sent to the listener.
                        mListener = null;
                    }
                }
            });
        }
    }

    /**
     * Set the header size and padding, based on the current scroll position.
     */
    private void updateHeaderTextSize() {
        if (mIsTwoPanel) {
            // The text size stays constant on two panel layouts.
            return;
        }

        // The pivot point for scaling should be middle of the starting side.
        if (isLayoutRtl()) {
            mLargeTextView.setPivotX(mLargeTextView.getWidth());
        } else {
            mLargeTextView.setPivotX(0);
        }
        mLargeTextView.setPivotY(mLargeTextView.getHeight() / 2);

        final int START_TEXT_SCALING_THRESHOLD_COEFFICIENT = 2;
        final int threshold = START_TEXT_SCALING_THRESHOLD_COEFFICIENT * mMinimumHeaderHeight;
        final int toolbarHeight = mToolbar.getLayoutParams().height;
        if (toolbarHeight >= threshold) {
            // Keep the text at maximum size since the header is smaller than threshold.
            mLargeTextView.setScaleX(1);
            mLargeTextView.setScaleY(1);
            setInterpolatedTitleMargin(1);
            return;
        }
        final float ratio = (toolbarHeight  - mMinimumHeaderHeight)
                / (float)(threshold - mMinimumHeaderHeight);
        final float minimumSize = mInvisiblePlaceholderTextView.getHeight();
        final float scale = (minimumSize + (mMaximumHeaderTextSize - minimumSize) * ratio)
                / mMaximumHeaderTextSize;

        mLargeTextView.setScaleX(scale);
        mLargeTextView.setScaleY(scale);
        setInterpolatedTitleMargin(ratio);
    }

    /**
     * Calculate the padding around mLargeTextView so that it will look appropriate once it
     * finishes moving into its target location/size.
     */
    private void calculateCollapsedLargeTitlePadding() {
        final Rect largeTextViewRect = new Rect();
        final Rect invisiblePlaceholderTextViewRect = new Rect();
        mToolbar.getBoundsOnScreen(largeTextViewRect);
        mInvisiblePlaceholderTextView.getBoundsOnScreen(invisiblePlaceholderTextViewRect);
        if (isLayoutRtl()) {
            mCollapsedTitleStartMargin = invisiblePlaceholderTextViewRect.right
                    - largeTextViewRect.right;
        } else {
            mCollapsedTitleStartMargin = invisiblePlaceholderTextViewRect.left
                    - largeTextViewRect.left;
        }

        // Distance between top of toolbar to the center of the target rectangle.
        final int desiredTopToCenter = (
                invisiblePlaceholderTextViewRect.top + invisiblePlaceholderTextViewRect.bottom)
                / 2 - largeTextViewRect.top;
        // Padding needed on the mLargeTextView so that it has the same amount of
        // padding as the target rectangle.
        mCollapsedTitleBottomMargin = desiredTopToCenter - mLargeTextView.getHeight() / 2;
    }

    /**
     * Interpolate the title's margin size. When {@param x}=1, use the maximum title margins.
     * When {@param x}=0, use the margin values taken from {@link #mInvisiblePlaceholderTextView}.
     */
    private void setInterpolatedTitleMargin(float x) {
        final FrameLayout.LayoutParams layoutParams
                = (FrameLayout.LayoutParams) mLargeTextView.getLayoutParams();
        layoutParams.bottomMargin = (int) (mCollapsedTitleBottomMargin * (1 - x)
                + mMaximumTitleMargin * x) ;
        layoutParams.setMarginStart((int) (mCollapsedTitleStartMargin * (1 - x)
                + mMaximumTitleMargin * x));
        mLargeTextView.setLayoutParams(layoutParams);
    }

    private void updatePhotoTintAndDropShadow() {
        // We need to use toolbarLayoutParams to determine the height, since the layout
        // params can be updated before the height change is reflected inside the View#getHeight().
        final int toolbarHeight = mToolbar.getLayoutParams().height;

        if (toolbarHeight <= mMinimumHeaderHeight && !mIsTwoPanel) {
            mPhotoViewContainer.setElevation(mToolbarElevation);
        } else {
            mPhotoViewContainer.setElevation(0);
        }

        // Reuse an existing mColorFilter (to avoid GC pauses) to change the photo's tint.
        mPhotoView.clearColorFilter();

        final float ratio;
        final float intermediateRatio;
        if (!mIsTwoPanel) {
            // Ratio of current size to maximum size of the header.
            ratio =  (toolbarHeight  - mMinimumHeaderHeight)
                    / (float) (mMaximumHeaderHeight - mMinimumHeaderHeight) ;
            // The value that "ratio" will have when the header is at its
            // starting/intermediate size.
            intermediateRatio = (mIntermediateHeaderHeight - mMinimumHeaderHeight)
                    / (float) (mMaximumHeaderHeight - mMinimumHeaderHeight);
        } else {
            // Set ratio and intermediateRatio to the same arbitrary value, so that
            // the math below considers us to be in the intermediate position. The specific
            // values are not very important.
            ratio = 0.5f;
            intermediateRatio = 0.5f;
        }

        final float linearBeforeMiddle = Math.max(1 - (1 - ratio) / intermediateRatio, 0);

        // Want a function with a derivative of 0 at x=0. I don't want it to grow too
        // slowly before x=0.5. x^1.1 satisfies both requirements.
        final float EXPONENT_ALMOST_ONE = 1.1f;
        final float semiLinearBeforeMiddle = (float) Math.pow(linearBeforeMiddle,
                EXPONENT_ALMOST_ONE);
        mColorMatrix.reset();
        mColorMatrix.setSaturation(semiLinearBeforeMiddle);
        mColorMatrix.postConcat(alphaMatrix(ratio, Color.WHITE));

        final float colorAlpha;
        if (mPhotoView.isBasedOffLetterTile()) {
            // Since the letter tile only has white and grey, tint it more slowly. Otherwise
            // it will be completely invisible before we reach the intermediate point.
            final float SLOWING_FACTOR = 1.6f;
            float linearBeforeMiddleish = Math.max(1 - (1 - ratio) / intermediateRatio
                    / SLOWING_FACTOR, 0);
            colorAlpha = 1 - (float) Math.pow(linearBeforeMiddleish, EXPONENT_ALMOST_ONE);
            mColorMatrix.postConcat(alphaMatrix(colorAlpha, mHeaderTintColor));
        } else {
            colorAlpha = 1 - semiLinearBeforeMiddle;
            mColorMatrix.postConcat(multiplyBlendMatrix(mHeaderTintColor, colorAlpha));
        }

        mColorFilter.setColorMatrix(mColorMatrix);
        mPhotoView.setColorFilter(mColorFilter);
        // Tell the photo view what tint we are trying to achieve. Depending on the type of
        // drawable used, the photo view may or may not use this tint.
        mPhotoView.setTint(((int) (0xFF * colorAlpha)) << 24 | (mHeaderTintColor & 0xffffff));
    }

    /**
     * Simulates alpha blending an image with {@param color}.
     */
    private ColorMatrix alphaMatrix(float alpha, int color) {
        mAlphaMatrixValues[0] = Color.red(color) * alpha / 255;
        mAlphaMatrixValues[6] = Color.green(color) * alpha / 255;
        mAlphaMatrixValues[12] = Color.blue(color) * alpha / 255;
        mAlphaMatrixValues[4] = 255 * (1 - alpha);
        mAlphaMatrixValues[9] = 255 * (1 - alpha);
        mAlphaMatrixValues[14] = 255 * (1 - alpha);
        mWhitenessColorMatrix.set(mAlphaMatrixValues);
        return mWhitenessColorMatrix;
    }

    /**
     * Simulates multiply blending an image with a single {@param color}.
     *
     * Multiply blending is [Sa * Da, Sc * Dc]. See {@link android.graphics.PorterDuff}.
     */
    private ColorMatrix multiplyBlendMatrix(int color, float alpha) {
        mMultiplyBlendMatrixValues[0] = multiplyBlend(Color.red(color), alpha);
        mMultiplyBlendMatrixValues[6] = multiplyBlend(Color.green(color), alpha);
        mMultiplyBlendMatrixValues[12] = multiplyBlend(Color.blue(color), alpha);
        mMultiplyBlendMatrix.set(mMultiplyBlendMatrixValues);
        return mMultiplyBlendMatrix;
    }

    private float multiplyBlend(int color, float alpha) {
        return color * alpha / 255.0f + (1 - alpha);
    }

    private void updateLastEventPosition(MotionEvent event) {
        mLastEventPosition[0] = event.getX();
        mLastEventPosition[1] = event.getY();
    }

    private boolean motionShouldStartDrag(MotionEvent event) {
        final float deltaX = event.getX() - mLastEventPosition[0];
        final float deltaY = event.getY() - mLastEventPosition[1];
        final boolean draggedX = (deltaX > mTouchSlop || deltaX < -mTouchSlop);
        final boolean draggedY = (deltaY > mTouchSlop || deltaY < -mTouchSlop);
        return draggedY && !draggedX;
    }

    private float updatePositionAndComputeDelta(MotionEvent event) {
        final int VERTICAL = 1;
        final float position = mLastEventPosition[VERTICAL];
        updateLastEventPosition(event);
        return position - mLastEventPosition[VERTICAL];
    }

    private void smoothScrollBy(int delta) {
        if (delta == 0) {
            // Delta=0 implies the code calling smoothScrollBy is sloppy. We should avoid doing
            // this, since it prevents Views from being able to register any clicks for 250ms.
            throw new IllegalArgumentException("Smooth scrolling by delta=0 is "
                    + "pointless and harmful");
        }
        mScroller.startScroll(0, getScroll(), 0, delta);
        invalidate();
    }

    /**
     * Interpolator that enforces a specific starting velocity. This is useful to avoid a
     * discontinuity between dragging speed and flinging speed.
     *
     * Similar to a {@link android.view.animation.AccelerateInterpolator} in the sense that
     * getInterpolation() is a quadratic function.
     */
    private static class AcceleratingFlingInterpolator implements Interpolator {

        private final float mStartingSpeedPixelsPerFrame;
        private final float mDurationMs;
        private final int mPixelsDelta;
        private final float mNumberFrames;

        public AcceleratingFlingInterpolator(int durationMs, float startingSpeedPixelsPerSecond,
                int pixelsDelta) {
            mStartingSpeedPixelsPerFrame = startingSpeedPixelsPerSecond / getRefreshRate();
            mDurationMs = durationMs;
            mPixelsDelta = pixelsDelta;
            mNumberFrames = mDurationMs / getFrameIntervalMs();
        }

        @Override
        public float getInterpolation(float input) {
            final float animationIntervalNumber = mNumberFrames * input;
            final float linearDelta = (animationIntervalNumber * mStartingSpeedPixelsPerFrame)
                    / mPixelsDelta;
            // Add the results of a linear interpolator (with the initial speed) with the
            // results of a AccelerateInterpolator.
            if (mStartingSpeedPixelsPerFrame > 0) {
                return Math.min(input * input + linearDelta, 1);
            } else {
                // Initial fling was in the wrong direction, make sure that the quadratic component
                // grows faster in order to make up for this.
                return Math.min(input * (input - linearDelta) + linearDelta, 1);
            }
        }

        private float getRefreshRate() {
            DisplayInfo di = DisplayManagerGlobal.getInstance().getDisplayInfo(
                    Display.DEFAULT_DISPLAY);
            return di.refreshRate;
        }

        public long getFrameIntervalMs() {
            return (long)(1000 / getRefreshRate());
        }
    }
}
