/*
 * aTalk, android VoIP and Instant Messaging client
 * Copyright 2014 Eng Chong Meng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atalk.android.gui;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

import net.java.sip.communicator.service.contactlist.MetaContact;

import org.atalk.android.R;
import org.atalk.android.aTalkApp;
import org.atalk.android.gui.chat.ChatPanel;
import org.atalk.android.gui.chat.ChatSessionManager;
import org.atalk.android.gui.chatroomslist.ChatRoomListFragment;
import org.atalk.android.gui.contactlist.ContactListFragment;
import org.atalk.android.gui.fragment.ActionBarStatusFragment;
import org.atalk.android.gui.menu.MainMenuActivity;
import org.atalk.android.gui.util.EntityListHelper;
import org.atalk.android.gui.webview.WebViewFragment;
import org.osgi.framework.BundleContext;

import java.util.List;

import androidx.fragment.app.*;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import de.cketti.library.changelog.ChangeLog;
import timber.log.Timber;

/**
 * The main <tt>Activity</tt> for aTalk application with pager slider for both contact and chatRoom list windows.
 *
 * @author Eng Chong Meng
 */
public class aTalk extends MainMenuActivity implements EntityListHelper.TaskCompleted
{
    private final static int CONTACT_LIST = 0;
    private final static int CONFERENCE_LIST = 1;
    private final static int WEB_PAGE = 2;
    /**
     * The action that will show contacts.
     */
    public static final String ACTION_SHOW_CONTACTS = "org.atalk.show_contacts";

    private static Boolean mPrefChange = false;

    /**
     * The main pager view fragment containing the contact List
     */
    private ContactListFragment contactListFragment = null;

    /**
     * Variable caches instance state stored for example on rotate event to prevent from
     * recreating the contact list after rotation. It is passed as second argument of
     * {@link #handleIntent(Intent, Bundle)} when called from {@link #onNewIntent(Intent)}.
     */
    private Bundle mInstanceState;

    /**
     * The number of pages (wizard steps) to show.
     */
    private static final int NUM_PAGES = 3;

    /**
     * The pager widget, which handles animation and allows swiping horizontally to access previous
     * and next wizard steps.
     */
    private ViewPager mPager;

    /**
     * Called when the activity is starting. Initializes the corresponding call interface.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down then this
     * Bundle contains the data it most recently supplied in onSaveInstanceState(Bundle).
     * Note: Otherwise it is null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        // Checks if OSGi has been started and if not starts LauncherActivity which will restore this Activity from its Intent.
        if (postRestoreIntent()) {
            return;
        }

        setContentView(R.layout.main_view);
        if (savedInstanceState == null) {
            // Inserts ActionBar functionality
            getSupportFragmentManager().beginTransaction().add(new ActionBarStatusFragment(), "action_bar").commit();
        }

        // Instantiate a ViewPager and a PagerAdapter.
        mPager = findViewById(R.id.mainViewPager);
        // The pager adapter, which provides the pages to the view pager widget.
        PagerAdapter mPagerAdapter = new MainPagerAdapter(getSupportFragmentManager());
        mPager.setAdapter(mPagerAdapter);
        mPager.setPageTransformer(true, new DepthPageTransformer());

        handleIntent(getIntent(), savedInstanceState);

        // allow 15 seconds for first launch login to complete before showing history log if the activity is still active
        runOnUiThread(() -> {
            new Handler().postDelayed(() -> {
                ChangeLog cl = new ChangeLog(aTalk.this);
                if (cl.isFirstRun() && !isFinishing()) {
                    cl.getLogDialog().show();
                }
            }, 15000);
        });
    }

    /**
     * Called when new <tt>Intent</tt> is received(this <tt>Activity</tt> is launched in <tt>singleTask</tt> mode.
     *
     * @param intent new <tt>Intent</tt> data.
     */
    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        handleIntent(intent, mInstanceState);
    }

    /**
     * Decides what should be displayed based on supplied <tt>Intent</tt> and instance state.
     *
     * @param intent <tt>Activity</tt> <tt>Intent</tt>.
     * @param instanceState <tt>Activity</tt> instance state.
     */
    private void handleIntent(Intent intent, Bundle instanceState)
    {
        String action = intent.getAction();
        if (Intent.ACTION_SEARCH.equals(action)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            Timber.w("Search intent not handled for query: %s", query);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState)
    {
        super.onRestoreInstanceState(savedInstanceState);
        mInstanceState = savedInstanceState;
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        mInstanceState = null;
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // Re-init aTalk to refresh the newly selected language
        if (mPrefChange) {
            mPrefChange = false;
            finish();
            startActivity(aTalk.class);
        }
    }

    /*
     * If the user is currently looking at the first page, allow the system to handle the
     * Back button. This calls finish() on this activity and pops the back stack.
     */
    @Override
    public void onBackPressed()
    {
        if (mPager.getCurrentItem() == 0) {
            super.onBackPressed();
        }
        else {
            // Otherwise, select the previous page.
            mPager.setCurrentItem(mPager.getCurrentItem() - 1);
        }
    }

    /**
     * Called when an activity is destroyed.
     */
    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        synchronized (this) {
            BundleContext bundleContext = getBundleContext();
            if (bundleContext != null) {
                try {
                    stop(bundleContext);
                } catch (Throwable t) {
                    Timber.e(t, "Error stopping application:%s", t.getLocalizedMessage());
                    if (t instanceof ThreadDeath)
                        throw (ThreadDeath) t;
                }
            }
        }
    }

    public static void setPrefChange(boolean state)
    {
        mPrefChange = state;
    }

    /**
     * Handler for chatListFragment on completed execution of
     *
     * @see EntityListHelper#eraseEntityChatHistory(Context, Object, List, List)
     * @see EntityListHelper#eraseAllContactHistory(Context)
     */
    @Override
    public void onTaskComplete(Integer result)
    {
        if (result == EntityListHelper.CURRENT_ENTITY) {
            MetaContact clickedContact = contactListFragment.getClickedContact();
            ChatPanel clickedChat = ChatSessionManager.getActiveChat(clickedContact);
            if (clickedChat != null) {
                // force chatPanel to reload from DB
                clickedChat.clearMsgCache();
                contactListFragment.onCloseChat(clickedChat);
            }
        }
        else if (result == EntityListHelper.ALL_ENTITIES) {
            contactListFragment.onCloseAllChats();
        }
        else { // failed
            String errMsg = aTalkApp.getResString(R.string.service_gui_HISTORY_REMOVE_ERROR,
                    contactListFragment.getClickedContact().getDisplayName());
            aTalkApp.showToastMessage(errMsg);
        }
    }

    /**
     * A simple pager adapter that represents 3 Screen Slide PageFragment objects, in sequence.
     */
    private class MainPagerAdapter extends FragmentPagerAdapter
    {
        private MainPagerAdapter(FragmentManager fm)
        {
            super(fm);
        }

        @Override
        public Fragment getItem(int position)
        {
            if (position == CONTACT_LIST) {
                contactListFragment = new ContactListFragment();
                aTalkApp.setContactListFragment(contactListFragment);
                return contactListFragment;
            }
            else if (position == CONFERENCE_LIST) {
                return new ChatRoomListFragment();
            }
            else { // if (position == WEB_PAGE){
                return new WebViewFragment();
            }
        }

        @Override
        public int getCount()
        {
            return NUM_PAGES;
        }
    }

    private class DepthPageTransformer implements ViewPager.PageTransformer
    {
        private static final float MIN_SCALE = 0.75f;

        public void transformPage(View view, float position)
        {
            int pageWidth = view.getWidth();

            if (position < -1) { // [-Infinity,-1)
                // This page is way off-screen to the left.
                view.setAlpha(0);
            }
            else if (position <= 0) { // [-1,0]
                // Use the default slide transition when moving to the left page
                view.setAlpha(1);
                view.setTranslationX(0);
                view.setScaleX(1);
                view.setScaleY(1);
            }
            else if (position <= 1) { // (0,1]
                // Fade the page out.
                view.setAlpha(1 - position);

                // Counteract the default slide transition
                view.setTranslationX(pageWidth * -position);

                // Scale the page down (between MIN_SCALE and 1)
                float scaleFactor = MIN_SCALE + (1 - MIN_SCALE) * (1 - Math.abs(position));
                view.setScaleX(scaleFactor);
                view.setScaleY(scaleFactor);

            }
            else { // (1,+Infinity]
                // This page is way off-screen to the right.
                view.setAlpha(0);
            }
        }
    }
}
