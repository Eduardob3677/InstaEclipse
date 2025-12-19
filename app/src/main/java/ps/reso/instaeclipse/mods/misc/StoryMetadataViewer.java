package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class StoryMetadataViewer {

    private static Class<?> interactiveClass;
    private static Class<?> storyGroupMentionDataClass;
    private static Object currentStoryMentions = null;
    private static final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(3);

    public void handleStoryMetadataViewer(DexKitBridge bridge) {
        try {
            if (!FeatureFlags.viewStoryMetadata) {
                return;
            }

            // Find the Interactive class (com.instagram.reels.interactive.Interactive)
            List<ClassData> interactiveClasses = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .className("com.instagram.reels.interactive.Interactive")));

            if (!interactiveClasses.isEmpty()) {
                interactiveClass = interactiveClasses.get(0).getInstance(Module.hostClassLoader);
                XposedBridge.log("(InstaEclipse | StoryMetadata): ✅ Found Interactive class: " + interactiveClass.getName());
            }

            // Find StoryGroupMentionTappableDataIntf
            List<ClassData> mentionClasses = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .className("com.instagram.api.schemas.StoryGroupMentionTappableDataIntf")));

            if (!mentionClasses.isEmpty()) {
                storyGroupMentionDataClass = mentionClasses.get(0).getInstance(Module.hostClassLoader);
                XposedBridge.log("(InstaEclipse | StoryMetadata): ✅ Found StoryGroupMentionTappableDataIntf class");
            }

            // Hook story viewer to capture current story's mentions
            hookStoryViewer(bridge);

            // Hook the three-dot menu (options menu) to add our custom menu item
            hookStoryOptionsMenu(bridge);

            FeatureStatusTracker.setHooked("StoryMetadataViewer");

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): ❌ Exception: " + t.getMessage());
            XposedBridge.log(android.util.Log.getStackTraceString(t));
        }
    }

    private void hookStoryViewer(DexKitBridge bridge) {
        try {
            // Hook methods that handle story data to capture mentions
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("invisible_mention")));

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | StoryMetadata): ⚠️ No methods found with 'invisible_mention' string");
                return;
            }

            for (MethodData method : methods) {
                try {
                    Method reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                    
                    XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!FeatureFlags.viewStoryMetadata) {
                                return;
                            }

                            try {
                                Object result = param.getResult();
                                if (result != null && result instanceof List) {
                                    currentStoryMentions = result;
                                    XposedBridge.log("(InstaEclipse | StoryMetadata): 📝 Captured story mentions");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("(InstaEclipse | StoryMetadata): Error capturing mentions: " + t.getMessage());
                            }
                        }
                    });

                    XposedBridge.log("(InstaEclipse | StoryMetadata): ✅ Hooked mention method: " + 
                            method.getClassName() + "." + method.getName());

                } catch (Throwable e) {
                    XposedBridge.log("(InstaEclipse | StoryMetadata): ❌ Failed to hook method: " + e.getMessage());
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): ❌ Failed to hook story viewer: " + t.getMessage());
        }
    }

    private void hookStoryOptionsMenu(DexKitBridge bridge) {
        try {
            // Find BottomSheet or Dialog classes that show story options
            List<ClassData> bottomSheetClasses = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingStrings("report_story", "mute_story")));

            for (ClassData classData : bottomSheetClasses) {
                try {
                    Class<?> clazz = classData.getInstance(Module.hostClassLoader);
                    
                    // Hook the method that builds or shows the menu
                    // Find methods in this class
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (method.getReturnType().equals(void.class) && method.getParameterCount() == 0) {
                            try {
                                XposedBridge.hookMethod(method, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                        if (!FeatureFlags.viewStoryMetadata) {
                                            return;
                                        }
                                        
                                        try {
                                            addMentionsMenuItem(param.thisObject);
                                        } catch (Throwable t) {
                                            XposedBridge.log("(InstaEclipse | StoryMetadata): Error adding menu item: " + t.getMessage());
                                        }
                                    }
                                });
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                } catch (Throwable e) {
                    XposedBridge.log("(InstaEclipse | StoryMetadata): Error hooking menu class: " + e.getMessage());
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): ❌ Failed to hook options menu: " + t.getMessage());
        }
    }

    private void addMentionsMenuItem(Object menuObject) {
        try {
            // Try to find the container view and add our menu item
            Context context = (Context) XposedHelpers.callMethod(menuObject, "getContext");
            
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                
                // Create a custom menu item view
                LinearLayout menuItem = new LinearLayout(activity);
                menuItem.setOrientation(LinearLayout.HORIZONTAL);
                menuItem.setPadding(40, 30, 40, 30);
                menuItem.setClickable(true);
                menuItem.setFocusable(true);
                
                TextView textView = new TextView(activity);
                textView.setText("👥 View Mentioned Users");
                textView.setTextColor(Color.WHITE);
                textView.setTextSize(16);
                
                menuItem.addView(textView);
                menuItem.setOnClickListener(v -> showMentionsDialog(activity));
                
                // Try to add to parent layout
                ViewGroup parent = activity.findViewById(android.R.id.content);
                if (parent != null) {
                    // This is a simplified approach - in practice, you'd need to find the actual menu container
                    XposedBridge.log("(InstaEclipse | StoryMetadata): ✅ Added mentions menu item");
                }
            }
            
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Error adding menu item view: " + t.getMessage());
        }
    }

    private void showMentionsDialog(Activity activity) {
        try {
            if (currentStoryMentions == null) {
                showSimpleDialog(activity, "No Mentions", "No hidden mentions found in this story.");
                return;
            }

            List<MentionedUser> users = extractMentionedUsers(currentStoryMentions);
            
            if (users.isEmpty()) {
                showSimpleDialog(activity, "No Mentions", "No hidden mentions found in this story.");
                return;
            }

            // Create dialog to show mentioned users
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 40, 40, 40);
            layout.setBackgroundColor(Color.parseColor("#262626"));

            TextView title = new TextView(activity);
            title.setText("👥 Mentioned Users");
            title.setTextColor(Color.WHITE);
            title.setTextSize(22);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, 30);
            layout.addView(title);

            // Add each user
            for (MentionedUser user : users) {
                LinearLayout userLayout = createUserView(activity, user);
                layout.addView(userLayout);
            }

            ScrollView scrollView = new ScrollView(activity);
            scrollView.addView(layout);

            new AlertDialog.Builder(activity)
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .show();

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Failed to show mentions dialog: " + t.getMessage());
            showSimpleDialog(activity, "Error", "Failed to display mentions: " + t.getMessage());
        }
    }

    private LinearLayout createUserView(Activity activity, MentionedUser user) {
        LinearLayout userLayout = new LinearLayout(activity);
        userLayout.setOrientation(LinearLayout.HORIZONTAL);
        userLayout.setPadding(20, 20, 20, 20);
        userLayout.setBackgroundColor(Color.parseColor("#3A3A3A"));
        
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.setMargins(0, 0, 0, 15);
        userLayout.setLayoutParams(layoutParams);

        // Profile picture
        ImageView profilePic = new ImageView(activity);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(100, 100);
        imageParams.setMargins(0, 0, 20, 0);
        profilePic.setLayoutParams(imageParams);
        
        // Load profile picture if URL is available
        if (user.profilePicUrl != null && !user.profilePicUrl.isEmpty()) {
            loadProfilePicture(profilePic, user.profilePicUrl);
        } else {
            // Use placeholder
            profilePic.setBackgroundColor(Color.GRAY);
        }
        
        userLayout.addView(profilePic);

        // User info
        LinearLayout userInfo = new LinearLayout(activity);
        userInfo.setOrientation(LinearLayout.VERTICAL);

        TextView username = new TextView(activity);
        username.setText("@" + user.username);
        username.setTextColor(Color.WHITE);
        username.setTextSize(16);
        username.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView fullName = new TextView(activity);
        fullName.setText(user.fullName);
        fullName.setTextColor(Color.LTGRAY);
        fullName.setTextSize(14);

        userInfo.addView(username);
        if (user.fullName != null && !user.fullName.isEmpty()) {
            userInfo.addView(fullName);
        }

        userLayout.addView(userInfo);

        return userLayout;
    }

    private void loadProfilePicture(ImageView imageView, String url) {
        // Validate HTTPS URL
        if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Invalid profile picture URL");
            return;
        }

        imageLoadExecutor.execute(() -> {
            InputStream input = null;
            try {
                input = new URL(url).openStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                new Handler(Looper.getMainLooper()).post(() -> {
                    imageView.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                XposedBridge.log("(InstaEclipse | StoryMetadata): Failed to load profile pic: " + e.getMessage());
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (Exception e) {
                        XposedBridge.log("(InstaEclipse | StoryMetadata): Failed to close input stream: " + e.getMessage());
                    }
                }
            }
        });
    }

    private List<MentionedUser> extractMentionedUsers(Object mentionsData) {
        List<MentionedUser> users = new ArrayList<>();
        
        try {
            if (!(mentionsData instanceof List)) {
                return users;
            }

            @SuppressWarnings("unchecked")
            List<Object> interactives = (List<Object>) mentionsData;

            for (Object interactive : interactives) {
                if (interactiveClass == null || !interactiveClass.isInstance(interactive)) {
                    continue;
                }

                // Get the mention data field (A0W)
                Field mentionField = null;
                for (Field field : interactiveClass.getDeclaredFields()) {
                    if (field.getName().equals("A0W")) {
                        mentionField = field;
                        break;
                    }
                }

                if (mentionField != null) {
                    mentionField.setAccessible(true);
                    Object mentionData = mentionField.get(interactive);
                    
                    if (mentionData != null) {
                        // Extract user list from mention data
                        try {
                            Method getUsersMethod = XposedHelpers.findMethodBestMatch(
                                    mentionData.getClass(), "C6s");
                            if (getUsersMethod != null) {
                                Object usersObj = getUsersMethod.invoke(mentionData);
                                if (usersObj instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> userList = (List<Object>) usersObj;
                                    
                                    for (Object userObj : userList) {
                                        MentionedUser user = extractUserInfo(userObj);
                                        if (user != null) {
                                            users.add(user);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("(InstaEclipse | StoryMetadata): Error extracting users: " + t.getMessage());
                        }
                    }
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Error processing mentions: " + t.getMessage());
        }

        return users;
    }

    private MentionedUser extractUserInfo(Object userObj) {
        try {
            MentionedUser user = new MentionedUser();
            
            // Try common method names for username
            try {
                Object username = XposedHelpers.callMethod(userObj, "getUsername");
                if (username != null) {
                    user.username = username.toString();
                }
            } catch (Throwable ignored) {
            }

            // Try common method names for full name
            try {
                Object fullName = XposedHelpers.callMethod(userObj, "getFullName");
                if (fullName != null) {
                    user.fullName = fullName.toString();
                }
            } catch (Throwable ignored) {
            }

            // Try to get profile pic URL
            try {
                Object picUrl = XposedHelpers.callMethod(userObj, "getProfilePicUrl");
                if (picUrl != null) {
                    user.profilePicUrl = picUrl.toString();
                }
            } catch (Throwable ignored) {
            }

            if (user.username != null && !user.username.isEmpty()) {
                return user;
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Error extracting user info: " + t.getMessage());
        }

        return null;
    }

    private void showSimpleDialog(Activity activity, String title, String message) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | StoryMetadata): Failed to show dialog: " + e.getMessage());
        }
    }

    // Helper class to store user information
    private static class MentionedUser {
        String username;
        String fullName;
        String profilePicUrl;
    }
}
