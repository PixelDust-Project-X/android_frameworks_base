/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ITaskStackListener;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.MutableBoolean;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.misc.Console;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskGrouping;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.views.TaskStackViewLayoutAlgorithm;
import com.android.systemui.recents.views.TaskViewHeader;
import com.android.systemui.recents.views.TaskViewTransform;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

/**
 * Annotation for a method that is only called from the system user's SystemUI process and will be
 * proxied to the current user.
 */
@interface ProxyFromSystemToCurrentUser {}
/**
 * Annotation for a method that may be called from any user's SystemUI process and will be proxied
 * to the system user.
 */
@interface ProxyFromAnyToSystemUser {}

/** A proxy implementation for the recents component */
public class Recents extends SystemUI
        implements ActivityOptions.OnAnimationStartedListener, RecentsComponent {

    final public static String EXTRA_TRIGGERED_FROM_ALT_TAB = "triggeredFromAltTab";
    final public static String EXTRA_TRIGGERED_FROM_HOME_KEY = "triggeredFromHomeKey";
    final public static String EXTRA_RECENTS_VISIBILITY = "recentsVisibility";

    // Owner proxy events
    final public static String ACTION_PROXY_NOTIFY_RECENTS_VISIBLITY_TO_OWNER =
            "action_notify_recents_visibility_change";
    final public static String ACTION_PROXY_SCREEN_PINNING_REQUEST_TO_OWNER =
            "action_screen_pinning_request";

    final public static String ACTION_START_ENTER_ANIMATION = "action_start_enter_animation";
    final public static String ACTION_TOGGLE_RECENTS_ACTIVITY = "action_toggle_recents_activity";
    final public static String ACTION_HIDE_RECENTS_ACTIVITY = "action_hide_recents_activity";

    final static int sMinToggleDelay = 350;

    public final static String sToggleRecentsAction = "com.android.systemui.recents.SHOW_RECENTS";
    public final static String sRecentsPackage = "com.android.systemui";
    public final static String sRecentsActivity = "com.android.systemui.recents.RecentsActivity";

    /**
     * An implementation of ITaskStackListener, that allows us to listen for changes to the system
     * task stacks and update recents accordingly.
     */
    class TaskStackListenerImpl extends ITaskStackListener.Stub implements Runnable {
        Handler mHandler;

        public TaskStackListenerImpl(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onTaskStackChanged() {
            // Debounce any task stack changes
            mHandler.removeCallbacks(this);
            mHandler.post(this);
        }

        /** Preloads the next task */
        public void run() {
            // Temporarily skip this if multi stack is enabled
            if (mConfig.multiWindowEnabled) return;

            RecentsConfiguration config = RecentsConfiguration.getInstance();
            if (config.svelteLevel == RecentsConfiguration.SVELTE_NONE) {
                RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
                SystemServicesProxy ssp = loader.getSystemServicesProxy();
                ActivityManager.RunningTaskInfo runningTaskInfo = ssp.getTopMostTask();

                // Load the next task only if we aren't svelte
                RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
                loader.preloadTasks(plan, true /* isTopTaskHome */);
                RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
                // This callback is made when a new activity is launched and the old one is paused
                // so ignore the current activity and try and preload the thumbnail for the
                // previous one.
                if (runningTaskInfo != null) {
                    launchOpts.runningTaskId = runningTaskInfo.id;
                }
                launchOpts.numVisibleTasks = 2;
                launchOpts.numVisibleTaskThumbnails = 2;
                launchOpts.onlyLoadForCache = true;
                launchOpts.onlyLoadPausedActivities = true;
                loader.loadTasks(mContext, plan, launchOpts);
            }
        }
    }

    /**
     * A proxy for Recents events which happens strictly for the owner.
     */
    class RecentsOwnerEventProxyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_PROXY_NOTIFY_RECENTS_VISIBLITY_TO_OWNER:
                    visibilityChanged(context,
                            intent.getBooleanExtra(EXTRA_RECENTS_VISIBILITY, false));
                    break;
                case ACTION_PROXY_SCREEN_PINNING_REQUEST_TO_OWNER:
                    onStartScreenPinning(context);
                    break;
            }
        }
    }

    static RecentsTaskLoadPlan sInstanceLoadPlan;
    static Recents sInstance;

    SystemServicesProxy mSystemServicesProxy;
    Handler mHandler;
    TaskStackListenerImpl mTaskStackListener;
    RecentsOwnerEventProxyReceiver mProxyBroadcastReceiver;
    RecentsAppWidgetHost mAppWidgetHost;
    boolean mBootCompleted;
    boolean mStartAnimationTriggered;
    boolean mCanReuseTaskStackViews = true;

    // Task launching
    RecentsConfiguration mConfig;
    Rect mSearchBarBounds = new Rect();
    Rect mTaskStackBounds = new Rect();
    Rect mLastTaskViewBounds = new Rect();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    int mStatusBarHeight;
    int mNavBarHeight;
    int mNavBarWidth;
    int mTaskBarHeight;

    // Header (for transition)
    TaskViewHeader mHeaderBar;
    final Object mHeaderBarLock = new Object();
    TaskStackView mDummyStackView;

    // Variables to keep track of if we need to start recents after binding
    boolean mTriggeredFromAltTab;
    long mLastToggleTime;

    Bitmap mThumbnailTransitionBitmapCache;
    Task mThumbnailTransitionBitmapCacheKey;

    public Recents() {
    }

    /**
     * Gets the singleton instance and starts it if needed. On the primary user on the device, this
     * component gets started as a normal {@link SystemUI} component. On a secondary user, this
     * lifecycle doesn't exist, so we need to start it manually here if needed.
     */
    public static Recents getInstanceAndStartIfNeeded(Context ctx) {
        if (sInstance == null) {
            sInstance = new Recents();
            sInstance.mContext = ctx;
            sInstance.start();
            sInstance.onBootCompleted();
        }
        return sInstance;
    }

    /** Creates a new broadcast intent */
    static Intent createLocalBroadcastIntent(Context context, String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT |
                Intent.FLAG_RECEIVER_FOREGROUND);
        return intent;
    }

    /** Initializes the Recents. */
    @ProxyFromSystemToCurrentUser
    @Override
    public void start() {
        if (sInstance == null) {
            sInstance = this;
        }
        Resources res = mContext.getResources();
        RecentsTaskLoader.initialize(mContext);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mSystemServicesProxy = new SystemServicesProxy(mContext);
        mHandler = new Handler();
        mAppWidgetHost = new RecentsAppWidgetHost(mContext, Constants.Values.App.AppWidgetHostId);

        // Register the task stack listener
        mTaskStackListener = new TaskStackListenerImpl(mHandler);
        mSystemServicesProxy.registerTaskStackListener(mTaskStackListener);

        // Only the owner has the callback to update the SysUI visibility flags, so all non-owner
        // instances of RecentsComponent needs to notify the owner when the visibility
        // changes.
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            mProxyBroadcastReceiver = new RecentsOwnerEventProxyReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Recents.ACTION_PROXY_NOTIFY_RECENTS_VISIBLITY_TO_OWNER);
            filter.addAction(Recents.ACTION_PROXY_SCREEN_PINNING_REQUEST_TO_OWNER);
            mContext.registerReceiverAsUser(mProxyBroadcastReceiver, UserHandle.CURRENT, filter,
                    null, mHandler);
        }

        // Initialize some static datastructures
        TaskStackViewLayoutAlgorithm.initializeCurve();
        // Initialize the static configuration resources
        mConfig = RecentsConfiguration.initialize(mContext, mSystemServicesProxy);
        mStatusBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height);
        mNavBarWidth = res.getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_width);
        mTaskBarHeight = res.getDimensionPixelSize(R.dimen.recents_task_bar_height);
        mDummyStackView = new TaskStackView(mContext, new TaskStack());
        mHeaderBar = (TaskViewHeader) inflater.inflate(R.layout.recents_task_view_header,
                null, false);
        reloadHeaderBarLayout(true /* tryAndBindSearchWidget */);

        // When we start, preload the data associated with the previous recent tasks.
        // We can use a new plan since the caches will be the same.
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, true /* isTopTaskHome */);
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.numVisibleTasks = loader.getApplicationIconCacheSize();
        launchOpts.numVisibleTaskThumbnails = loader.getThumbnailCacheSize();
        launchOpts.onlyLoadForCache = true;
        loader.loadTasks(mContext, plan, launchOpts);
        putComponent(Recents.class, this);
    }

    @Override
    public void onBootCompleted() {
        mBootCompleted = true;
        reloadHeaderBarLayout(true /* tryAndBindSearchWidget */);
    }

    /** Shows the Recents. */
    @ProxyFromSystemToCurrentUser
    @Override
    public void showRecents(boolean triggeredFromAltTab, View statusBarView) {
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            showRecentsInternal(triggeredFromAltTab);
        } else {
            Intent intent = createLocalBroadcastIntent(mContext,
                    RecentsUserEventProxyReceiver.ACTION_PROXY_SHOW_RECENTS_TO_USER);
            intent.putExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, triggeredFromAltTab);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }

    void showRecentsInternal(boolean triggeredFromAltTab) {
        mTriggeredFromAltTab = triggeredFromAltTab;

        try {
            showRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    /** Hides the Recents. */
    @ProxyFromSystemToCurrentUser
    @Override
    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            hideRecentsInternal(triggeredFromAltTab, triggeredFromHomeKey);
        } else {
            Intent intent = createLocalBroadcastIntent(mContext,
                    RecentsUserEventProxyReceiver.ACTION_PROXY_HIDE_RECENTS_TO_USER);
            intent.putExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, triggeredFromAltTab);
            intent.putExtra(EXTRA_TRIGGERED_FROM_HOME_KEY, triggeredFromHomeKey);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }

    void hideRecentsInternal(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (mBootCompleted) {
            // Defer to the activity to handle hiding recents, if it handles it, then it must still
            // be visible
            Intent intent = createLocalBroadcastIntent(mContext, ACTION_HIDE_RECENTS_ACTIVITY);
            intent.putExtra(EXTRA_TRIGGERED_FROM_ALT_TAB, triggeredFromAltTab);
            intent.putExtra(EXTRA_TRIGGERED_FROM_HOME_KEY, triggeredFromHomeKey);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }

    /** Toggles the Recents activity. */
    @ProxyFromSystemToCurrentUser
    @Override
    public void toggleRecents(Display display, int layoutDirection, View statusBarView) {
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            toggleRecentsInternal();
        } else {
            Intent intent = createLocalBroadcastIntent(mContext,
                    RecentsUserEventProxyReceiver.ACTION_PROXY_TOGGLE_RECENTS_TO_USER);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }

    void toggleRecentsInternal() {
        mTriggeredFromAltTab = false;

        try {
            toggleRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    /** Preloads info for the Recents activity. */
    @ProxyFromSystemToCurrentUser
    @Override
    public void preloadRecents() {
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            preloadRecentsInternal();
        } else {
            Intent intent = createLocalBroadcastIntent(mContext,
                    RecentsUserEventProxyReceiver.ACTION_PROXY_PRELOAD_RECENTS_TO_USER);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }

    void preloadRecentsInternal() {
        // Preload only the raw task list into a new load plan (which will be consumed by the
        // RecentsActivity) only if there is a task to animate to.
        ActivityManager.RunningTaskInfo topTask = mSystemServicesProxy.getTopMostTask();
        MutableBoolean topTaskHome = new MutableBoolean(true);
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        sInstanceLoadPlan = loader.createLoadPlan(mContext);
        if (topTask != null && !mSystemServicesProxy.isRecentsTopMost(topTask, topTaskHome)) {
            sInstanceLoadPlan.preloadRawTasks(topTaskHome.value);
            loader.preloadTasks(sInstanceLoadPlan, topTaskHome.value);
            TaskStack stack = sInstanceLoadPlan.getTaskStack();
            if (stack.getTaskCount() > 0) {
                preCacheThumbnailTransitionBitmapAsync(topTask, stack, mDummyStackView,
                        topTaskHome.value);
            }
        }
    }

    @Override
    public void cancelPreloadingRecents() {
        // Do nothing
    }

    void showRelativeAffiliatedTask(boolean showNextTask) {
        // Return early if there is no focused stack
        int focusedStackId = mSystemServicesProxy.getFocusedStack();
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        RecentsTaskLoadPlan plan = loader.createLoadPlan(mContext);
        loader.preloadTasks(plan, true /* isTopTaskHome */);
        TaskStack focusedStack = plan.getTaskStack();

        // Return early if there are no tasks in the focused stack
        if (focusedStack == null || focusedStack.getTaskCount() == 0) return;

        ActivityManager.RunningTaskInfo runningTask = mSystemServicesProxy.getTopMostTask();
        // Return early if there is no running task (can't determine affiliated tasks in this case)
        if (runningTask == null) return;
        // Return early if the running task is in the home stack (optimization)
        if (mSystemServicesProxy.isInHomeStack(runningTask.id)) return;

        // Find the task in the recents list
        ArrayList<Task> tasks = focusedStack.getTasks();
        Task toTask = null;
        ActivityOptions launchOpts = null;
        int taskCount = tasks.size();
        int numAffiliatedTasks = 0;
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            if (task.key.id == runningTask.id) {
                TaskGrouping group = task.group;
                Task.TaskKey toTaskKey;
                if (showNextTask) {
                    toTaskKey = group.getNextTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_next_affiliated_task_target,
                            R.anim.recents_launch_next_affiliated_task_source);
                } else {
                    toTaskKey = group.getPrevTaskInGroup(task);
                    launchOpts = ActivityOptions.makeCustomAnimation(mContext,
                            R.anim.recents_launch_prev_affiliated_task_target,
                            R.anim.recents_launch_prev_affiliated_task_source);
                }
                if (toTaskKey != null) {
                    toTask = focusedStack.findTaskWithId(toTaskKey.id);
                }
                numAffiliatedTasks = group.getTaskCount();
                break;
            }
        }

        // Return early if there is no next task
        if (toTask == null) {
            if (numAffiliatedTasks > 1) {
                if (showNextTask) {
                    mSystemServicesProxy.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_next_affiliated_task_bounce));
                } else {
                    mSystemServicesProxy.startInPlaceAnimationOnFrontMostApplication(
                            ActivityOptions.makeCustomInPlaceAnimation(mContext,
                                    R.anim.recents_launch_prev_affiliated_task_bounce));
                }
            }
            return;
        }

        // Keep track of actually launched affiliated tasks
        MetricsLogger.count(mContext, "overview_affiliated_task_launch", 1);

        // Launch the task
        if (toTask.isActive) {
            // Bring an active task to the foreground
            mSystemServicesProxy.moveTaskToFront(toTask.key.id, launchOpts);
        } else {
            mSystemServicesProxy.startActivityFromRecents(mContext, toTask.key.id,
                    toTask.activityLabel, launchOpts);
        }
    }

    @Override
    public void showNextAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_next", 1);
        showRelativeAffiliatedTask(true);
    }

    @Override
    public void showPrevAffiliatedTask() {
        // Keep track of when the affiliated task is triggered
        MetricsLogger.count(mContext, "overview_affiliated_task_prev", 1);
        showRelativeAffiliatedTask(false);
    }

    /** Updates on configuration change. */
    @ProxyFromSystemToCurrentUser
    public void onConfigurationChanged(Configuration newConfig) {
        if (mSystemServicesProxy.isForegroundUserSystem()) {
            configurationChanged();
        } else {
            Intent intent = createLocalBroadcastIntent(mContext,
                    RecentsUserEventProxyReceiver.ACTION_PROXY_CONFIG_CHANGE_TO_USER);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        }
    }
    void configurationChanged() {
        // Don't reuse task stack views if the configuration changes
        mCanReuseTaskStackViews = false;
        mConfig.updateOnConfigurationChange();
    }

    /**
     * Prepares the header bar layout for the next transition, if the task view bounds has changed
     * since the last call, it will attempt to re-measure and layout the header bar to the new size.
     *
     * @param tryAndBindSearchWidget if set, will attempt to fetch and bind the search widget if one
     *                               is not already bound (can be expensive)
     */
    void reloadHeaderBarLayout(boolean tryAndBindSearchWidget) {
        Rect windowRect = mSystemServicesProxy.getWindowRect();

        // Update the configuration for the current state
        mConfig.update(mContext, mSystemServicesProxy, mSystemServicesProxy.getWindowRect());

        if (tryAndBindSearchWidget) {
            // Try and pre-emptively bind the search widget on startup to ensure that we
            // have the right thumbnail bounds to animate to.
            // Note: We have to reload the widget id before we get the task stack bounds below
            if (mSystemServicesProxy.getOrBindSearchAppWidget(mContext, mAppWidgetHost) != null) {
                mConfig.getSearchBarBounds(windowRect,
                        mStatusBarHeight, mSearchBarBounds);
            }
        }
        mConfig.getAvailableTaskStackBounds(windowRect,
                mStatusBarHeight, (mConfig.hasTransposedNavBar ? mNavBarWidth : 0),
                mSearchBarBounds, mTaskStackBounds);
        int systemBarBottomInset = mConfig.hasTransposedNavBar ? 0 : mNavBarHeight;

        // Rebind the header bar and draw it for the transition
        TaskStackViewLayoutAlgorithm algo = mDummyStackView.getStackAlgorithm();
        Rect taskStackBounds = new Rect(mTaskStackBounds);
        taskStackBounds.bottom -= systemBarBottomInset;
        algo.computeRects(windowRect.width(), windowRect.height(), taskStackBounds);
        Rect taskViewBounds = algo.getUntransformedTaskViewBounds();
        if (!taskViewBounds.equals(mLastTaskViewBounds)) {
            mLastTaskViewBounds.set(taskViewBounds);

            int taskViewWidth = taskViewBounds.width();
            synchronized (mHeaderBarLock) {
                mHeaderBar.measure(
                    View.MeasureSpec.makeMeasureSpec(taskViewWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(mTaskBarHeight, View.MeasureSpec.EXACTLY));
                mHeaderBar.layout(0, 0, taskViewWidth, mTaskBarHeight);
            }
        }
    }

    /** Toggles the recents activity */
    void toggleRecentsActivity() {
        // If the user has toggled it too quickly, then just eat up the event here (it's better than
        // showing a janky screenshot).
        // NOTE: Ideally, the screenshot mechanism would take the window transform into account
        if ((SystemClock.elapsedRealtime() - mLastToggleTime) < sMinToggleDelay) {
            return;
        }

        // If Recents is the front most activity, then we should just communicate with it directly
        // to launch the first task or dismiss itself
        ActivityManager.RunningTaskInfo topTask = mSystemServicesProxy.getTopMostTask();
        MutableBoolean isTopTaskHome = new MutableBoolean(true);
        if (topTask != null && mSystemServicesProxy.isRecentsTopMost(topTask, isTopTaskHome)) {
            // Notify recents to toggle itself
            Intent intent = createLocalBroadcastIntent(mContext, ACTION_TOGGLE_RECENTS_ACTIVITY);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            mLastToggleTime = SystemClock.elapsedRealtime();
            return;
        } else {
            // Otherwise, start the recents activity
            showRecentsActivity(topTask, isTopTaskHome.value);
        }
    }

    /** Shows the recents activity if it is not already running */
    void showRecentsActivity() {
        // Check if the top task is in the home stack, and start the recents activity
        ActivityManager.RunningTaskInfo topTask = mSystemServicesProxy.getTopMostTask();
        MutableBoolean isTopTaskHome = new MutableBoolean(true);
        if (topTask == null || !mSystemServicesProxy.isRecentsTopMost(topTask, isTopTaskHome)) {
            showRecentsActivity(topTask, isTopTaskHome.value);
        }
    }

    /**
     * Creates the activity options for a unknown state->recents transition.
     */
    ActivityOptions getUnknownTransitionActivityOptions() {
        mStartAnimationTriggered = false;
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_unknown_enter,
                R.anim.recents_from_unknown_exit,
                mHandler, this);
    }

    /**
     * Creates the activity options for a home->recents transition.
     */
    ActivityOptions getHomeTransitionActivityOptions(boolean fromSearchHome) {
        mStartAnimationTriggered = false;
        if (fromSearchHome) {
            return ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.recents_from_search_launcher_enter,
                    R.anim.recents_from_search_launcher_exit,
                    mHandler, this);
        }
        return ActivityOptions.makeCustomAnimation(mContext,
                R.anim.recents_from_launcher_enter,
                R.anim.recents_from_launcher_exit,
                mHandler, this);
    }

    /**
     * Creates the activity options for an app->recents transition.
     */
    ActivityOptions getThumbnailTransitionActivityOptions(ActivityManager.RunningTaskInfo topTask,
            TaskStack stack, TaskStackView stackView) {

        // Update the destination rect
        Task toTask = new Task();
        TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                topTask.id, toTask);
        Rect toTaskRect = toTransform.rect;
        Bitmap thumbnail;
        if (mThumbnailTransitionBitmapCacheKey != null
                && mThumbnailTransitionBitmapCacheKey.key != null
                && mThumbnailTransitionBitmapCacheKey.key.equals(toTask.key)) {
            thumbnail = mThumbnailTransitionBitmapCache;
            mThumbnailTransitionBitmapCacheKey = null;
            mThumbnailTransitionBitmapCache = null;
        } else {
            preloadIcon(topTask);
            thumbnail = drawThumbnailTransitionBitmap(toTask, toTransform);
        }
        if (thumbnail != null) {
            mStartAnimationTriggered = false;
            return ActivityOptions.makeThumbnailAspectScaleDownAnimation(mDummyStackView,
                    thumbnail, toTaskRect.left, toTaskRect.top, toTaskRect.width(),
                    toTaskRect.height(), mHandler, this);
        }

        // If both the screenshot and thumbnail fails, then just fall back to the default transition
        return getUnknownTransitionActivityOptions();
    }

    /**
     * Preloads the icon of a task.
     */
    void preloadIcon(ActivityManager.RunningTaskInfo task) {

        // Ensure that we load the running task's icon
        RecentsTaskLoadPlan.Options launchOpts = new RecentsTaskLoadPlan.Options();
        launchOpts.runningTaskId = task.id;
        launchOpts.loadThumbnails = false;
        launchOpts.onlyLoadForCache = true;
        RecentsTaskLoader.getInstance().loadTasks(mContext, sInstanceLoadPlan, launchOpts);
    }

    /**
     * Caches the header thumbnail used for a window animation asynchronously into
     * {@link #mThumbnailTransitionBitmapCache}.
     */
    void preCacheThumbnailTransitionBitmapAsync(ActivityManager.RunningTaskInfo topTask,
            TaskStack stack, TaskStackView stackView, boolean isTopTaskHome) {
        preloadIcon(topTask);

        // Update the destination rect
        mDummyStackView.updateMinMaxScrollForStack(stack, mTriggeredFromAltTab, isTopTaskHome);
        final Task toTask = new Task();
        final TaskViewTransform toTransform = getThumbnailTransitionTransform(stack, stackView,
                topTask.id, toTask);
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                return drawThumbnailTransitionBitmap(toTask, toTransform);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                mThumbnailTransitionBitmapCache = bitmap;
                mThumbnailTransitionBitmapCacheKey = toTask;
            }
        }.execute();
    }

    /**
     * Draws the header of a task used for the window animation into a bitmap.
     */
    Bitmap drawThumbnailTransitionBitmap(Task toTask, TaskViewTransform toTransform) {
        if (toTransform != null && toTask.key != null) {
            Bitmap thumbnail;
            synchronized (mHeaderBarLock) {
                int toHeaderWidth = (int) (mHeaderBar.getMeasuredWidth() * toTransform.scale);
                int toHeaderHeight = (int) (mHeaderBar.getMeasuredHeight() * toTransform.scale);
                thumbnail = Bitmap.createBitmap(toHeaderWidth, toHeaderHeight,
                        Bitmap.Config.ARGB_8888);
                if (Constants.DebugFlags.App.EnableTransitionThumbnailDebugMode) {
                    thumbnail.eraseColor(0xFFff0000);
                } else {
                    Canvas c = new Canvas(thumbnail);
                    c.scale(toTransform.scale, toTransform.scale);
                    mHeaderBar.rebindToTask(toTask);
                    mHeaderBar.draw(c);
                    c.setBitmap(null);
                }
            }
            return thumbnail.createAshmemBitmap();
        }
        return null;
    }

    /** Returns the transition rect for the given task id. */
    TaskViewTransform getThumbnailTransitionTransform(TaskStack stack, TaskStackView stackView,
            int runningTaskId, Task runningTaskOut) {
        // Find the running task in the TaskStack
        Task task = null;
        ArrayList<Task> tasks = stack.getTasks();
        if (runningTaskId != -1) {
            // Otherwise, try and find the task with the
            int taskCount = tasks.size();
            for (int i = taskCount - 1; i >= 0; i--) {
                Task t = tasks.get(i);
                if (t.key.id == runningTaskId) {
                    task = t;
                    runningTaskOut.copyFrom(t);
                    break;
                }
            }
        }
        if (task == null) {
            // If no task is specified or we can not find the task just use the front most one
            task = tasks.get(tasks.size() - 1);
            runningTaskOut.copyFrom(task);
        }

        // Get the transform for the running task
        stackView.getScroller().setStackScrollToInitialState();
        mTmpTransform = stackView.getStackAlgorithm().getStackTransform(task,
                stackView.getScroller().getStackScroll(), mTmpTransform, null);
        return mTmpTransform;
    }

    /** Shows the recents activity */
    void showRecentsActivity(ActivityManager.RunningTaskInfo topTask, boolean isTopTaskHome) {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();

        // Update the header bar if necessary
        reloadHeaderBarLayout(false /* tryAndBindSearchWidget */);

        if (sInstanceLoadPlan == null) {
            // Create a new load plan if onPreloadRecents() was never triggered
            sInstanceLoadPlan = loader.createLoadPlan(mContext);
        }

        if (!sInstanceLoadPlan.hasTasks()) {
            loader.preloadTasks(sInstanceLoadPlan, isTopTaskHome);
        }
        TaskStack stack = sInstanceLoadPlan.getTaskStack();

        // Prepare the dummy stack for the transition
        mDummyStackView.updateMinMaxScrollForStack(stack, mTriggeredFromAltTab, isTopTaskHome);
        TaskStackViewLayoutAlgorithm.VisibilityReport stackVr =
                mDummyStackView.computeStackVisibilityReport();
        boolean hasRecentTasks = stack.getTaskCount() > 0;
        boolean useThumbnailTransition = (topTask != null) && !isTopTaskHome && hasRecentTasks;

        if (useThumbnailTransition) {

            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptions(topTask, stack,
                    mDummyStackView);
            if (opts != null) {
                startRecentsActivity(topTask, opts, false /* fromHome */,
                        false /* fromSearchHome */, true /* fromThumbnail */, stackVr);
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        if (!useThumbnailTransition) {
            // If there is no thumbnail transition, but is launching from home into recents, then
            // use a quick home transition and do the animation from home
            if (hasRecentTasks) {
                String homeActivityPackage = mSystemServicesProxy.getHomeActivityPackageName();
                String searchWidgetPackage =
                        Prefs.getString(mContext, Prefs.Key.SEARCH_APP_WIDGET_PACKAGE, null);

                // Determine whether we are coming from a search owned home activity
                boolean fromSearchHome = (homeActivityPackage != null) &&
                        homeActivityPackage.equals(searchWidgetPackage);
                ActivityOptions opts = getHomeTransitionActivityOptions(fromSearchHome);
                startRecentsActivity(topTask, opts, true /* fromHome */, fromSearchHome,
                        false /* fromThumbnail */, stackVr);
            } else {
                // Otherwise we do the normal fade from an unknown source
                ActivityOptions opts = getUnknownTransitionActivityOptions();
                startRecentsActivity(topTask, opts, true /* fromHome */,
                        false /* fromSearchHome */, false /* fromThumbnail */, stackVr);
            }
        }
        mLastToggleTime = SystemClock.elapsedRealtime();
    }

    /** Starts the recents activity */
    void startRecentsActivity(ActivityManager.RunningTaskInfo topTask,
              ActivityOptions opts, boolean fromHome, boolean fromSearchHome, boolean fromThumbnail,
              TaskStackViewLayoutAlgorithm.VisibilityReport vr) {
        // Update the configuration based on the launch options
        RecentsActivityLaunchState launchState = mConfig.getLaunchState();
        launchState.launchedFromHome = fromSearchHome || fromHome;
        launchState.launchedFromSearchHome = fromSearchHome;
        launchState.launchedFromAppWithThumbnail = fromThumbnail;
        launchState.launchedToTaskId = (topTask != null) ? topTask.id : -1;
        launchState.launchedWithAltTab = mTriggeredFromAltTab;
        launchState.launchedReuseTaskStackViews = mCanReuseTaskStackViews;
        launchState.launchedNumVisibleTasks = vr.numVisibleTasks;
        launchState.launchedNumVisibleThumbnails = vr.numVisibleThumbnails;
        launchState.launchedHasConfigurationChanged = false;

        Intent intent = new Intent(sToggleRecentsAction);
        intent.setClassName(sRecentsPackage, sRecentsActivity);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT);
        } else {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
        mCanReuseTaskStackViews = true;
    }

    /** Notifies the callbacks that the visibility of Recents has changed. */
    @ProxyFromAnyToSystemUser
    public static void notifyVisibilityChanged(Context context, SystemServicesProxy ssp,
            boolean visible) {
        if (ssp.isForegroundUserSystem()) {
            visibilityChanged(context, visible);
        } else {
            Intent intent = createLocalBroadcastIntent(context,
                    ACTION_PROXY_NOTIFY_RECENTS_VISIBLITY_TO_OWNER);
            intent.putExtra(EXTRA_RECENTS_VISIBILITY, visible);
            context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
        }
    }
    static void visibilityChanged(Context context, boolean visible) {
        // For the primary user, the context for the SystemUI component is the SystemUIApplication
        SystemUIApplication app = (SystemUIApplication)
                getInstanceAndStartIfNeeded(context.getApplicationContext()).mContext;
        PhoneStatusBar statusBar = app.getComponent(PhoneStatusBar.class);
        if (statusBar != null) {
            statusBar.updateRecentsVisibility(visible);
        }
    }

    /** Notifies the status bar to trigger screen pinning. */
    @ProxyFromAnyToSystemUser
    public static void startScreenPinning(Context context, SystemServicesProxy ssp) {
        if (ssp.isForegroundUserSystem()) {
            onStartScreenPinning(context);
        } else {
            Intent intent = createLocalBroadcastIntent(context,
                    ACTION_PROXY_SCREEN_PINNING_REQUEST_TO_OWNER);
            context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
        }
    }
    static void onStartScreenPinning(Context context) {
        // For the primary user, the context for the SystemUI component is the SystemUIApplication
        SystemUIApplication app = (SystemUIApplication)
                getInstanceAndStartIfNeeded(context.getApplicationContext()).mContext;
        PhoneStatusBar statusBar = app.getComponent(PhoneStatusBar.class);
        if (statusBar != null) {
            statusBar.showScreenPinningRequest(false);
        }
    }

    /**
     * Returns the preloaded load plan and invalidates it.
     */
    public static RecentsTaskLoadPlan consumeInstanceLoadPlan() {
        RecentsTaskLoadPlan plan = sInstanceLoadPlan;
        sInstanceLoadPlan = null;
        return plan;
    }

    /**** OnAnimationStartedListener Implementation ****/

    @Override
    public void onAnimationStarted() {
        // Notify recents to start the enter animation
        if (!mStartAnimationTriggered) {
            // There can be a race condition between the start animation callback and
            // the start of the new activity (where we register the receiver that listens
            // to this broadcast, so we add our own receiver and if that gets called, then
            // we know the activity has not yet started and we can retry sending the broadcast.
            BroadcastReceiver fallbackReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == Activity.RESULT_OK) {
                        mStartAnimationTriggered = true;
                        return;
                    }

                    // Schedule for the broadcast to be sent again after some time
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            onAnimationStarted();
                        }
                    }, 25);
                }
            };

            // Send the broadcast to notify Recents that the animation has started
            Intent intent = createLocalBroadcastIntent(mContext, ACTION_START_ENTER_ANIMATION);
            mContext.sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT, null,
                    fallbackReceiver, null, Activity.RESULT_CANCELED, null, null);
        }
    }
}
