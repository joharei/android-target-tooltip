package it.sephiroth.android.library.tooltip;

import android.app.Activity;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Build;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.TextView;

import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.ObjectAnimator;
import com.nineoldandroids.view.ViewHelper;

class ToolTipLayout extends ViewGroup {

	static final boolean DBG = TooltipManager.DBG;
	private static final String TAG = "ToolTipLayout";
	private final long showDelay;

	private boolean mAttached;
	private boolean mInitialized;

	private final int toolTipId;
	private final Rect viewRect;
	private final Rect drawRect;
	private final Rect tempRect;

	private final long showDuration;
	private final TooltipManager.ClosePolicy closePolity;
	private final View targetView;
	private final Point point;
	private final int textResId;
	private final int topRule;
	private final int maxWidth;

	private CharSequence text;
	TooltipManager.Gravity gravitiy;
	private int padding = 60;

	private View mView;
	private TextView mTextView;
	private final ToolTipTextDrawable mDrawable;

	public ToolTipLayout(Context context, TooltipManager.Builder builder) {
		super(context);

		this.toolTipId = builder.id;
		this.text = builder.text;
		this.gravitiy = builder.gravitiy;
		this.textResId = builder.textResId;
		this.maxWidth = builder.maxWidth;
		this.topRule = builder.actionbarSize;
		this.closePolity = builder.closePolicy;
		this.showDuration = builder.showDuration;
		this.showDelay = builder.showDelay;

		this.targetView = builder.view;
		this.point = builder.point;

		this.viewRect = new Rect();
		this.drawRect = new Rect();
		this.tempRect = new Rect();

		this.mDrawable = new ToolTipTextDrawable(builder);

		setVisibility(GONE);
		setHardwareAccelerated(true);
	}

	int getTooltipId() {
		return toolTipId;
	}

	protected void setHardwareAccelerated(boolean accelerated) {
		if (accelerated) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				if (isHardwareAccelerated()) {
					Paint hardwarePaint = new Paint();
					hardwarePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
					setLayerType(LAYER_TYPE_HARDWARE, hardwarePaint);
				}
				else {
					setLayerType(LAYER_TYPE_SOFTWARE, null);
				}
			}
			else {
				setDrawingCacheEnabled(true);
			}
		}
		else {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				setLayerType(LAYER_TYPE_SOFTWARE, null);
			}
			else {
				setDrawingCacheEnabled(true);
			}
		}
	}

	void doShow() {
		if (DBG) Log.i(TAG, "doShow");
		if (! isAttached()) return;
		initializeView();
		fadeIn();
	}

	void doHide() {
		if (DBG) Log.i(TAG, "doHide");
		if (! isAttached()) return;
		fadeOut();
	}

	Animator mShowAnimation;
	boolean mShowing;

	protected void fadeIn() {
		if (null != mShowAnimation || mShowing) return;
		if (DBG) Log.i(TAG, "fadeIn");

		mShowing = true;
		mShowAnimation = ObjectAnimator.ofFloat(this, "alpha", 0, 1);
		if (this.showDelay > 0) {
			mShowAnimation.setStartDelay(this.showDelay);
		}
		mShowAnimation.addListener(new Animator.AnimatorListener() {
			boolean cancelled;

			@Override
			public void onAnimationStart(final Animator animation) {
				setVisibility(View.VISIBLE);
				cancelled = false;
			}

			@Override
			public void onAnimationEnd(final Animator animation) {
				if (DBG) Log.i(TAG, "fadein::onAnimationEnd, cancelled: " + cancelled);
				if (null != tooltipListener && ! cancelled) {
					tooltipListener.onShowCompleted(ToolTipLayout.this);
				}
			}

			@Override
			public void onAnimationCancel(final Animator animation) {
				if (DBG) Log.i(TAG, "fadein::onAnimationCancel");
				cancelled = true;
			}

			@Override
			public void onAnimationRepeat(final Animator animation) {

			}
		});

		mShowAnimation.start();
		if (showDuration > 0) {
			getHandler().removeCallbacks(hideRunnable);
			getHandler().postDelayed(hideRunnable, showDuration);
		}
	}

	Runnable hideRunnable = new Runnable() {
		@Override
		public void run() {
			onClose();
		}
	};

	boolean isShowing() {
		return mShowing;
	}

	void removeFromParent() {
		if (DBG) Log.i(TAG, "removeFromParent: " + toolTipId);
		ViewParent parent = getParent();
		if (null != parent) {
			if (null != getHandler()) {
				getHandler().removeCallbacks(hideRunnable);
			}
			((ViewGroup) parent).removeView(ToolTipLayout.this);
		}
	}

	protected void fadeOut() {
		if (! isAttached() || ! mShowing) return;
		if (DBG) Log.i(TAG, "fadeOut");

		if (null != mShowAnimation) {
			mShowAnimation.cancel();
		}

		mShowing = false;

		float alpha = ViewHelper.getAlpha(this);
		Animator animation = ObjectAnimator.ofFloat(this, "alpha", alpha, 0);
		animation.addListener(new Animator.AnimatorListener() {
			boolean cancelled;

			@Override
			public void onAnimationStart(final Animator animation) {
				cancelled = false;
			}

			@Override
			public void onAnimationEnd(final Animator animation) {
				if (DBG) Log.i(TAG, "fadeout::onAnimationEnd, cancelled: " + cancelled);
				if (cancelled) return;

				if (null != tooltipListener) {
					tooltipListener.onHideCompleted(ToolTipLayout.this);
				}
				mShowAnimation = null;
			}

			@Override
			public void onAnimationCancel(final Animator animation) {
				if (DBG) Log.i(TAG, "fadeout::onAnimationCancel");
				cancelled = true;
			}

			@Override
			public void onAnimationRepeat(final Animator animation) {

			}
		});
		animation.start();
	}

	@Override
	protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b) {
		if (DBG) Log.i(TAG, "onLayout, changed: " + changed + ", " + l + ", " + t + ", " + r + ", " + b);

		//  The layout has actually already been performed and the positions
		//  cached.  Apply the cached values to the children.
		final int count = getChildCount();

		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() != GONE) {
				ViewGroup.LayoutParams st = child.getLayoutParams();
				child.layout(child.getLeft(), child.getTop(), child.getMeasuredWidth(), child.getMeasuredHeight());
			}
		}
		calculatePositions();
	}

	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		Log.i(TAG, "onMeasure");
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int myWidth = - 1;
		int myHeight = - 1;

		final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

		// Record our dimensions if they are known;
		if (widthMode != MeasureSpec.UNSPECIFIED) {
			myWidth = widthSize;
		}

		if (heightMode != MeasureSpec.UNSPECIFIED) {
			myHeight = heightSize;
		}

		Log.v(TAG, "myWidth: " + myWidth);
		Log.v(TAG, "myHeight: " + myHeight);

		final int count = getChildCount();

		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() != GONE) {
				int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(myWidth, MeasureSpec.AT_MOST);
				int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST);
				child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
			}
		}

		setMeasuredDimension(myWidth, myHeight);
	}

	@Override
	protected void onAttachedToWindow() {
		if (DBG) Log.i(TAG, "onAttachedToWindow");
		super.onAttachedToWindow();
		mAttached = true;
	}

	@Override
	protected void onDetachedFromWindow() {
		if (DBG) Log.i(TAG, "onDetachedFromWindow");
		super.onDetachedFromWindow();
		mAttached = false;
	}

	private void initializeView() {
		if (! isAttached() || mInitialized) return;
		mInitialized = true;

		if (DBG) Log.i(TAG, "initializeView");

		LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

		mView = LayoutInflater.from(getContext()).inflate(textResId, this, false);
		mView.setLayoutParams(params);
		mView.setBackgroundDrawable(mDrawable);
		mView.setPadding(padding, padding, padding, padding);

		mTextView = (TextView) mView.findViewById(android.R.id.text1);
		mTextView.setText(Html.fromHtml((String) this.text));
		if (maxWidth > - 1) {
			mTextView.setMaxWidth(maxWidth);
		}

		this.addView(mView);
	}

	private void calculatePositions() {
		if (! isAttached()) return;
		if (DBG) Log.i(TAG, "calculatePositions: " + gravitiy);

		Rect screenRect = new Rect();
		Window window = ((Activity) getContext()).getWindow();
		window.getDecorView().getWindowVisibleDisplayFrame(screenRect);
		screenRect.top += topRule;

		ToolTipTextDrawable drawable = (ToolTipTextDrawable) mView.getBackground();

		// get the global visible rect for the target targetView
		if (null != targetView) {
			targetView.getGlobalVisibleRect(viewRect);
		}
		else {
			viewRect.set(point.x, point.y, point.x, point.y);
		}

		int width = mView.getWidth();
		int height = mView.getMeasuredHeight();
		if (DBG) {
			Log.d(TAG, "screen.size: " + screenRect);
			Log.d(TAG, "textView.size: " + width + "x" + height);
		}

		// get the destination point
		Point point = new Point();

		//@formatter:off
		if (gravitiy == TooltipManager.Gravity.BOTTOM) {
			drawRect.set(viewRect.centerX() - width / 2,
			             viewRect.bottom,
			             viewRect.centerX() + width / 2,
			             viewRect.bottom + height);

			point.x = viewRect.centerX();
			point.y = viewRect.bottom;

			if (! screenRect.contains(drawRect)) {
				if (drawRect.right > screenRect.right) {
					drawRect.offset(screenRect.right - drawRect.right, 0);
				}
				else if (drawRect.left < screenRect.left) {
					drawRect.offset(- drawRect.left, 0);
				}
				if (drawRect.bottom > screenRect.bottom) {
					// this means there's no enough space!
					gravitiy = TooltipManager.Gravity.TOP;
					calculatePositions();
					return;
				}
			}
		}
		else if (gravitiy == TooltipManager.Gravity.TOP) {
			drawRect.set(viewRect.centerX() - width / 2,
			             viewRect.top - height,
			             viewRect.centerX() + width / 2,
			             viewRect.top);

			Log.d(TAG, "final bounds: " + drawRect);
			Log.d(TAG, "contains: " + screenRect.contains(drawRect));

			point.x = viewRect.centerX();
			point.y = viewRect.top;

			if (! screenRect.contains(drawRect)) {
				if (drawRect.right > screenRect.right) {
					drawRect.offset(screenRect.right - drawRect.right, 0);
				}
				else if (drawRect.left < screenRect.left) {
					drawRect.offset(- drawRect.left, 0);
				}
				if (drawRect.top < screenRect.top) {
					// this means there's no enough space!
					gravitiy = TooltipManager.Gravity.BOTTOM;
					calculatePositions();
					return;
				}
			}
		}
		else if (gravitiy == TooltipManager.Gravity.RIGHT) {
			drawRect.set(viewRect.right,
			             viewRect.centerY() - height / 2,
			             viewRect.right + width,
			             viewRect.centerY() + height / 2);

			Log.d(TAG, "final bounds: " + drawRect);
			Log.d(TAG, "contains: " + screenRect.contains(drawRect));

			point.x = viewRect.right;
			point.y = viewRect.centerY();

			if (! screenRect.contains(drawRect)) {
				if (drawRect.bottom > screenRect.bottom) {
					drawRect.offset(0, screenRect.bottom - drawRect.bottom);
				}
				else if (drawRect.top < screenRect.top) {
					drawRect.offset(0, screenRect.top - drawRect.top);
				}
				if (drawRect.right > screenRect.right) {
					// this means there's no enough space!
					gravitiy = TooltipManager.Gravity.LEFT;
					calculatePositions();
					return;
				}
			}
		}
		else if (gravitiy == TooltipManager.Gravity.LEFT) {
			drawRect.set(viewRect.left - width,
			             viewRect.centerY() - height / 2,
			             viewRect.left,
			             viewRect.centerY() + height / 2);

			Log.d(TAG, "final bounds: " + drawRect);
			Log.d(TAG, "contains: " + screenRect.contains(drawRect));

			point.x = viewRect.left;
			point.y = viewRect.centerY();

			if (! screenRect.contains(drawRect)) {
				if (drawRect.bottom > screenRect.bottom) {
					drawRect.offset(0, screenRect.bottom - drawRect.bottom);
				}
				else if (drawRect.top < screenRect.top) {
					drawRect.offset(0, screenRect.top - drawRect.top);
				}
				if (drawRect.left < screenRect.left) {
					// this means there's no enough space!
					gravitiy = TooltipManager.Gravity.RIGHT;
					calculatePositions();
					return;
				}
			}
		}
		//@formatter:on

		Log.d(TAG, "drawRect: " + drawRect);

		// translate the textview
		ViewHelper.setTranslationX(mView, drawRect.left);
		ViewHelper.setTranslationY(mView, drawRect.top);

		// get the global rect for the textview
		mView.getGlobalVisibleRect(tempRect);

		point.x -= tempRect.left;
		point.y -= tempRect.top;

		if(gravitiy == TooltipManager.Gravity.LEFT || gravitiy == TooltipManager.Gravity.RIGHT) {
			point.y -= padding/2;
		} else {
			point.x -= padding/2;
		}

		drawable.setAnchor(gravitiy, padding / 2);
		drawable.setDestinationPoint(point);

		if(DBG) {
			Log.d(TAG, "viewRect: " + viewRect);
			Log.d(TAG, "tempRect: " + tempRect);
			Log.d(TAG, "point: " + point);
		}
	}

	public boolean isAttached() {
		return mAttached;
	}

	public void setText(final CharSequence text) {
		if (DBG) Log.i(TAG, "setText: " + text);
		this.text = text;
		if (null != mTextView) {
			mTextView.setText(Html.fromHtml((String) text));
		}
	}

	@Override
	public boolean onTouchEvent(final MotionEvent event) {
		if (DBG) Log.i(TAG, "onTouchEvent");
		if (! mAttached) return false;
		if (event.getAction() != MotionEvent.ACTION_DOWN || null == drawRect) return false;

		if (closePolity == TooltipManager.ClosePolicy.TouchOutside || closePolity == TooltipManager.ClosePolicy.TouchInside) {
			if (closePolity == TooltipManager.ClosePolicy.TouchInside) {
				if (drawRect.contains((int) event.getX(), (int) event.getY())) {
					onClose();
					return true;
				}
			}
			else {
				onClose();
				return true;
			}
		}
		return false;
	}

	private void onClose() {
		if (DBG) Log.i(TAG, "onClose");
		if (null == getHandler()) return;
		getHandler().removeCallbacks(hideRunnable);
		if (null != closeListener) {
			closeListener.onClose(this);
		}
	}

	private OnCloseListener closeListener;
	private OnToolTipListener tooltipListener;

	void setOnCloseListener(OnCloseListener listener) {
		this.closeListener = listener;
	}

	void setOnToolTipListener(OnToolTipListener listener) {
		this.tooltipListener = listener;
	}

	static interface OnCloseListener {
		void onClose(ToolTipLayout layout);
	}

	static interface OnToolTipListener {
		void onHideCompleted(ToolTipLayout layout);

		void onShowCompleted(ToolTipLayout layout);
	}
}