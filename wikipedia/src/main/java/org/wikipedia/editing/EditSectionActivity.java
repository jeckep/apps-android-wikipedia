package org.wikipedia.editing;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.squareup.otto.Bus;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import org.mediawiki.api.json.Api;
import org.mediawiki.api.json.ApiException;
import org.mediawiki.api.json.RequestBuilder;
import org.wikipedia.PageTitle;
import org.wikipedia.R;
import org.wikipedia.ThemedActionBarActivity;
import org.wikipedia.Utils;
import org.wikipedia.ViewAnimations;
import org.wikipedia.WikipediaApp;
import org.wikipedia.analytics.EditFunnel;
import org.wikipedia.analytics.LoginFunnel;
import org.wikipedia.editing.summaries.EditSummaryFragment;
import org.wikipedia.editing.richtext.SyntaxHighlighter;
import org.wikipedia.login.LoginActivity;
import org.wikipedia.login.LoginResult;
import org.wikipedia.login.LoginTask;
import org.wikipedia.login.User;
import org.wikipedia.page.LinkMovementMethodExt;
import org.wikipedia.page.PageProperties;

public class EditSectionActivity extends ThemedActionBarActivity {
    public static final String ACTION_EDIT_SECTION = "org.wikipedia.edit_section";
    public static final String EXTRA_TITLE = "org.wikipedia.edit_section.title";
    public static final String EXTRA_SECTION_ID = "org.wikipedia.edit_section.sectionid";
    public static final String EXTRA_SECTION_HEADING = "org.wikipedia.edit_section.sectionheading";
    public static final String EXTRA_PAGE_PROPS = "org.wikipedia.edit_section.pageprops";

    private WikipediaApp app;
    private Bus bus;

    private PageTitle title;
    public PageTitle getPageTitle() {
        return title;
    }

    private int sectionID;
    private String sectionHeading;
    private PageProperties pageProps;

    private String sectionWikitext;

    private EditText sectionText;
    private boolean sectionTextModified = false;
    private boolean sectionTextFirstLoad = true;

    private View sectionProgress;
    private View sectionContainer;
    private View sectionError;

    private View abusefilterContainer;
    private ImageView abuseFilterImage;
    private TextView abusefilterTitle;
    private TextView abusefilterText;

    private AbuseFilterEditResult abusefilterEditResult;

    private CaptchaHandler captchaHandler;

    private EditPreviewFragment editPreviewFragment;

    private EditSummaryFragment editSummaryFragment;

    private EditFunnel funnel;

    private ProgressDialog progressDialog;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_section);

        if (!getIntent().getAction().equals(ACTION_EDIT_SECTION)) {
            throw new RuntimeException("Much wrong action. Such exception. Wow");
        }

        app = (WikipediaApp)getApplicationContext();

        title = getIntent().getParcelableExtra(EXTRA_TITLE);
        sectionID = getIntent().getIntExtra(EXTRA_SECTION_ID, 0);
        sectionHeading = getIntent().getStringExtra(EXTRA_SECTION_HEADING);
        pageProps = getIntent().getParcelableExtra(EXTRA_PAGE_PROPS);

        progressDialog = new ProgressDialog(this);
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.setMessage(getString(R.string.dialog_saving_in_progress));

        getSupportActionBar().setTitle("");

        sectionText = (EditText) findViewById(R.id.edit_section_text);

        new SyntaxHighlighter(this, sectionText);

        sectionProgress = findViewById(R.id.edit_section_load_progress);
        sectionContainer = findViewById(R.id.edit_section_container);
        sectionError = findViewById(R.id.edit_section_error);
        Button sectionErrorRetry = (Button) findViewById(R.id.edit_section_error_retry);

        abusefilterContainer = findViewById(R.id.edit_section_abusefilter_container);
        abuseFilterImage = (ImageView) findViewById(R.id.edit_section_abusefilter_image);
        abusefilterTitle = (TextView) findViewById(R.id.edit_section_abusefilter_title);
        abusefilterText = (TextView) findViewById(R.id.edit_section_abusefilter_text);

        captchaHandler = new CaptchaHandler(this, title.getSite(), progressDialog, sectionContainer, "", null);

        editPreviewFragment = (EditPreviewFragment) getSupportFragmentManager().findFragmentById(R.id.edit_section_preview_fragment);
        editSummaryFragment = (EditSummaryFragment) getSupportFragmentManager().findFragmentById(R.id.edit_section_summary_fragment);

        updateEditLicenseText();
        editSummaryFragment.setTitle(title);

        bus = app.getBus();
        bus.register(this);

        funnel = app.getFunnelManager().getEditFunnel(title);

        // Only send the editing start log event if the activity is created for the first time
        if (savedInstanceState == null) {
            funnel.logStart();
        }

        if (savedInstanceState != null && savedInstanceState.containsKey("sectionWikitext")) {
            sectionWikitext = savedInstanceState.getString("sectionWikitext");
        }

        captchaHandler.restoreState(savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey("abusefilter")) {
            abusefilterEditResult = savedInstanceState.getParcelable("abusefilter");
            handleAbuseFilter();
        }

        sectionErrorRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ViewAnimations.crossFade(sectionError, sectionProgress);
                fetchSectionText();
            }
        });


        Utils.setTextDirection(sectionText, title.getSite().getLanguage());

        fetchSectionText();

        if (savedInstanceState != null && savedInstanceState.containsKey("sectionTextModified")) {
            sectionTextModified = savedInstanceState.getBoolean("sectionTextModified");
        }

        sectionText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (sectionTextFirstLoad) {
                    sectionTextFirstLoad = false;
                    return;
                }
                if (!sectionTextModified) {
                    sectionTextModified = true;
                    // update the actionbar menu, which will enable the Next button.
                    supportInvalidateOptionsMenu();
                }
            }
        });

        // set focus to the EditText, but keep the keyboard hidden until the user changes the cursor location:
        sectionText.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void updateEditLicenseText() {
        TextView editLicenseText = (TextView) findViewById(R.id.edit_section_license_text);
        if (app.getUserInfoStorage().isLoggedIn()) {
            editLicenseText.setText(Html.fromHtml(getString(R.string.edit_save_action_license_logged_in)));
        } else {
            editLicenseText.setText(Html.fromHtml(getString(R.string.edit_save_action_license_anon)));
        }

        editLicenseText.setMovementMethod(new LinkMovementMethodExt(new LinkMovementMethodExt.UrlHandler() {
            @Override
            public void onUrlClick(String url) {
                if (url.equals("https://#login")) {
                    funnel.logLoginAttempt();
                    Intent loginIntent = new Intent(EditSectionActivity.this, LoginActivity.class);
                    loginIntent.putExtra(LoginActivity.LOGIN_REQUEST_SOURCE, LoginFunnel.SOURCE_EDIT);
                    loginIntent.putExtra(LoginActivity.EDIT_SESSION_TOKEN, funnel.getEditSessionToken());
                    startActivityForResult(loginIntent, LoginActivity.REQUEST_LOGIN);
                } else {
                    Utils.handleExternalLink(EditSectionActivity.this, Uri.parse(url));
                }
            }
        }));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == LoginActivity.REQUEST_LOGIN) {
            if (resultCode == LoginActivity.RESULT_LOGIN_SUCCESS) {
                updateEditLicenseText();
                funnel.logLoginSuccess();
            } else {
                funnel.logLoginFailure();
            }
        }
    }

    private void doSave() {
        captchaHandler.hideCaptcha();
        editSummaryFragment.saveSummary();
        app.getEditTokenStorage().get(title.getSite(), new EditTokenStorage.TokenRetrievedCallback() {
            @Override
            public void onTokenRetrieved(final String token) {

                String summaryText = TextUtils.isEmpty(sectionHeading) ? "" : ("/* " + sectionHeading + " */ ");
                summaryText += editPreviewFragment.getSummary();

                new DoEditTask(EditSectionActivity.this, title, sectionText.getText().toString(),
                        sectionID, token, summaryText, app.getUserInfoStorage().isLoggedIn()) {
                    @Override
                    public void onBeforeExecute() {
                        if (!isFinishing()) {
                            progressDialog.show();
                        }
                    }

                    @Override
                    public RequestBuilder buildRequest(Api api) {
                        return captchaHandler.populateBuilder(super.buildRequest(api));
                    }

                    @Override
                    public void onCatch(Throwable caught) {
                        if (isFinishing() || !progressDialog.isShowing()) {
                            // no longer attached to activity!
                            return;
                        }
                        if (caught instanceof EditingException) {
                            handleEditingException((EditingException) caught);
                            return;
                        }
                        if (!(caught instanceof ApiException)) {
                            throw new RuntimeException(caught);
                        }
                        Log.d("Wikipedia", "Caught " + caught.toString());
                        showRetryDialog();
                    }

                    @Override
                    public void onFinish(EditingResult result) {
                        if (isFinishing() || !progressDialog.isShowing()) {
                            // no longer attached to activity!
                            return;
                        }
                        if (result instanceof SuccessEditResult) {
                            funnel.logSaved(((SuccessEditResult) result).getRevID());
                            progressDialog.dismiss();

                            //Build intent that includes the section we were editing, so we can scroll to it later
                            Intent data = new Intent();
                            data.putExtra(EXTRA_SECTION_ID, sectionID);
                            setResult(EditHandler.RESULT_REFRESH_PAGE, data);
                            Toast.makeText(EditSectionActivity.this, R.string.edit_saved_successfully, Toast.LENGTH_LONG).show();
                            Utils.hideSoftKeyboard(EditSectionActivity.this);
                            finish();
                        } else if (result instanceof CaptchaResult) {
                            if (captchaHandler.isActive()) {
                                // Captcha entry failed!
                                funnel.logCaptchaFailure();
                            }
                            captchaHandler.handleCaptcha((CaptchaResult) result);
                            funnel.logCaptchaShown();
                        } else if (result instanceof AbuseFilterEditResult) {
                            abusefilterEditResult = (AbuseFilterEditResult) result;
                            handleAbuseFilter();
                            if (abusefilterEditResult.getType() == AbuseFilterEditResult.TYPE_ERROR) {
                                editPreviewFragment.hide();
                            }
                        } else if (result instanceof SpamBlacklistEditResult) {
                            Crouton.makeText(
                                    EditSectionActivity.this,
                                    getString(R.string.editing_error_spamblacklist, ((SpamBlacklistEditResult) result).getDomain()),
                                    Style.ALERT
                            ).show();
                            progressDialog.dismiss();
                            editPreviewFragment.hide();
                        } else {
                            funnel.logError(result.getResult());
                            // Expand to do everything.
                            onCatch(null);
                        }

                    }
                }.execute();
            }

            @Override
            public void onTokenFailed(Throwable caught) {
                if (isFinishing()) {
                    return;
                }
                if (!(caught instanceof ApiException)) {
                    throw new RuntimeException(caught);
                }
                showRetryDialog();
            }
        });
    }

    private void showRetryDialog() {
        final AlertDialog retryDialog = new AlertDialog.Builder(EditSectionActivity.this)
                .setMessage(R.string.dialog_message_edit_failed)
                .setPositiveButton(R.string.dialog_message_edit_failed_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doSave();
                        dialog.dismiss();
                        progressDialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.dialog_message_edit_failed_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        progressDialog.dismiss();
                    }
                }).create();
        retryDialog.show();
    }

    private void handleEditingException(EditingException ee) {
        if (app.getUserInfoStorage().isLoggedIn() && (ee.getCode().equals("badtoken")
                || ee.getCode().equals("assertuserfailed"))) {
            // looks like our session expired.
            app.getEditTokenStorage().clearAllTokens();
            app.getCookieManager().clearAllCookies();

            User user = app.getUserInfoStorage().getUser();
            new LoginTask(app, app.getPrimarySite(), user.getUsername(), user.getPassword()) {
                @Override
                public void onFinish(LoginResult result) {
                    if (result.getCode().equals("Success")) {
                        doSave();
                    } else {
                        progressDialog.dismiss();
                        ViewAnimations.crossFade(sectionText, sectionError);
                        sectionError.setVisibility(View.VISIBLE);
                    }
                }
            }.execute();
        } else if (ee.getCode().equals("blocked") || ee.getCode().equals("wikimedia-globalblocking-ipblocked")) {
            // User is blocked, locally or globally
            // If they were anon, canedit does not catch this, so we can't show them the locked pencil
            // If they not anon, this means they were blocked in the interim between opening the edit
            // window and clicking save. Less common, but might as well handle it
            progressDialog.dismiss();
            AlertDialog.Builder builder = new AlertDialog.Builder(EditSectionActivity.this);
            builder.setTitle(R.string.user_blocked_from_editing_title);
            if (app.getUserInfoStorage().isLoggedIn()) {
                builder.setMessage(R.string.user_logged_in_blocked_from_editing);
                builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                });
            } else {
                builder.setMessage(R.string.user_anon_blocked_from_editing);
                builder.setPositiveButton(R.string.nav_item_login, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                        Intent loginIntent = new Intent(EditSectionActivity.this, LoginActivity.class);
                        loginIntent.putExtra(LoginActivity.LOGIN_REQUEST_SOURCE, LoginFunnel.SOURCE_BLOCKED);
                        startActivity(loginIntent);
                    }
                });
                builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                });
            }
            builder.show();
        } else {
            // an unknown error occurred, so just dismiss the progress dialog and show a message.
            progressDialog.dismiss();
            Crouton.makeText(this, String.format(getString(R.string.edit_save_unknown_error), ee.getCode()), Style.ALERT).show();
        }
    }

    private void handleAbuseFilter() {
        if (abusefilterEditResult == null) {
            return;
        }
        if (abusefilterEditResult.getType() == AbuseFilterEditResult.TYPE_ERROR) {
            funnel.logAbuseFilterError(abusefilterEditResult.getCode());
            abuseFilterImage.setImageResource(R.drawable.abusefilter_disallow);
            abusefilterTitle.setText(getString(R.string.abusefilter_title_disallow));
            abusefilterText.setText(Html.fromHtml(getString(R.string.abusefilter_text_disallow)));
        } else {
            funnel.logAbuseFilterWarning(abusefilterEditResult.getCode());
            abuseFilterImage.setImageResource(R.drawable.abusefilter_warn);
            abusefilterTitle.setText(getString(R.string.abusefilter_title_warn));
            abusefilterText.setText(Html.fromHtml(getString(R.string.abusefilter_text_warn)));
        }

        Utils.hideSoftKeyboard(this);
        ViewAnimations.fadeIn(abusefilterContainer, new Runnable() {
            @Override
            public void run() {
                supportInvalidateOptionsMenu();
            }
        });

        if (progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }


    private void cancelAbuseFilter() {
        abusefilterEditResult = null;
        ViewAnimations.fadeOut(abusefilterContainer, new Runnable() {
            @Override
            public void run() {
                supportInvalidateOptionsMenu();
            }
        });
    }

    /**
     * Executes a click of the actionbar button, and performs the appropriate action
     * based on the current state of the button.
     */
    public void clickNextButton() {
        if (editSummaryFragment.isActive()) {
            //we're showing the custom edit summary window, so close it and
            //apply the provided summary.
            editSummaryFragment.hide();
            editPreviewFragment.setCustomSummary(editSummaryFragment.getSummary());
        } else if (editPreviewFragment.isActive()) {
            //we're showing the Preview window, which means that the next step is to save it!
            if (abusefilterEditResult != null) {
                //if the user was already shown an AbuseFilter warning, and they're ignoring it:
                funnel.logAbuseFilterWarningIgnore(abusefilterEditResult.getCode());
            }
            doSave();
            funnel.logSaveAttempt();
        } else {
            //we must be showing the editing window, so show the Preview.
            Utils.hideSoftKeyboard(this);
            editPreviewFragment.showPreview(title, sectionText.getText().toString());
            funnel.logPreview();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_save_section:
                clickNextButton();
                return true;
            default:
                throw new RuntimeException("WAT");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_edit_section, menu);
        MenuItem item = menu.findItem(R.id.menu_save_section);

        if (editSummaryFragment.isActive()) {
            item.setTitle(getString(R.string.edit_next));
        } else if (editPreviewFragment.isActive()) {
            item.setTitle(getString(R.string.edit_done));
        } else {
            item.setTitle(getString(R.string.edit_next));
        }

        if (abusefilterEditResult != null) {
            if (abusefilterEditResult.getType() == AbuseFilterEditResult.TYPE_ERROR) {
                item.setEnabled(false);
            } else {
                item.setEnabled(true);
            }
        } else {
            item.setEnabled(sectionTextModified);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            View v = getLayoutInflater().inflate(R.layout.item_edit_actionbar_button, null);
            item.setActionView(v);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            v.setLayoutParams(params);
            TextView txtView = (TextView) v.findViewById(R.id.edit_actionbar_button_text);
            txtView.setText(item.getTitle());
            txtView.setTypeface(null, item.isEnabled() ? Typeface.BOLD : Typeface.NORMAL);
            v.setTag(item);
            v.setClickable(true);
            v.setEnabled(item.isEnabled());
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onOptionsItemSelected((MenuItem) view.getTag());
                }
            });

            if (editSummaryFragment.isActive()) {
                v.setBackgroundResource(R.drawable.button_selector_progressive);
            } else if (editPreviewFragment.isActive()) {
                v.setBackgroundResource(R.drawable.button_selector_complete);
            } else {
                v.setBackgroundResource(R.drawable.button_selector_progressive);
            }
        }

        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sectionWikitext", sectionWikitext);
        outState.putParcelable("abusefilter", abusefilterEditResult);
        outState.putBoolean("sectionTextModified", sectionTextModified);
        captchaHandler.saveState(outState);
    }

    private void fetchSectionText() {
        if (sectionWikitext == null) {
            new FetchSectionWikitextTask(this, title, sectionID) {
                @Override
                public void onFinish(String result) {
                    sectionWikitext = result;
                    displaySectionText();
                }

                @Override
                public void onCatch(Throwable caught) {
                    ViewAnimations.crossFade(sectionProgress, sectionError);
                    // Not sure why this is required, but without it tapping retry hides langLinksError
                    // FIXME: INVESTIGATE WHY THIS HAPPENS!
                    // Also happens in {@link PageViewFragment}
                    sectionError.setVisibility(View.VISIBLE);
                }
            }.execute();
        } else {
            displaySectionText();
        }
    }

    private void displaySectionText() {
        sectionText.setText(sectionWikitext);
        ViewAnimations.crossFade(sectionProgress, sectionContainer);
        supportInvalidateOptionsMenu();

        if (pageProps != null && pageProps.getEditProtectionStatus() != null) {
            String message;
            if (pageProps.getEditProtectionStatus().equals("sysop")) {
                message = getString(R.string.page_protected_sysop);
            } else if (pageProps.getEditProtectionStatus().equals("autoconfirmed")) {
                message = getString(R.string.page_protected_autoconfirmed);
            } else {
                message = getString(R.string.page_protected_other, pageProps.getEditProtectionStatus());
            }
            Crouton.makeText(this,
                    message,
                    Style.INFO).show();
        }
    }

    /**
     * Shows the custom edit summary input fragment, where the user may enter a summary
     * that's different from the standard summary tags.
     */
    public void showCustomSummary() {
        editSummaryFragment.show();
    }

    @Override
    public void onBackPressed() {
        if (captchaHandler.isActive()) {
            captchaHandler.cancelCaptcha();
        }
        if (abusefilterEditResult != null) {
            if (abusefilterEditResult.getType() == AbuseFilterEditResult.TYPE_WARNING) {
                funnel.logAbuseFilterWarningBack(abusefilterEditResult.getCode());
            }
            cancelAbuseFilter();
            return;
        }
        if (editSummaryFragment.handleBackPressed()) {
            return;
        }
        if (editPreviewFragment.handleBackPressed()) {
            return;
        }

        Utils.hideSoftKeyboard(this);

        if (sectionTextModified) {
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setMessage(getString(R.string.edit_abandon_confirm));
            alert.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                    finish();
                }
            });
            alert.setNegativeButton(getString(R.string.no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });
            alert.create().show();
        } else {
            finish();
        }
    }

    @Override
    protected void onStop() {
        if (progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        if (bus != null) {
            bus.unregister(this);
            bus = null;
            Log.d("Wikipedia", "Deregistering bus");
        }
        super.onStop();
    }
}
