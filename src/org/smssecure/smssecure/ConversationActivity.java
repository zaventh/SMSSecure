/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnKeyListener;
import android.view.ViewStub;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.AlertDialogWrapper;
import com.google.protobuf.ByteString;

import org.smssecure.smssecure.TransportOptions.OnTransportChangedListener;
import org.smssecure.smssecure.components.EmojiDrawer;
import org.smssecure.smssecure.components.EmojiToggle;
import org.smssecure.smssecure.components.SendButton;
import org.smssecure.smssecure.contacts.ContactAccessor;
import org.smssecure.smssecure.contacts.ContactAccessor.ContactData;
import org.smssecure.smssecure.crypto.KeyExchangeInitiator;
import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SecurityEvent;
import org.smssecure.smssecure.crypto.SessionUtil;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.database.DraftDatabase.Draft;
import org.smssecure.smssecure.database.DraftDatabase.Drafts;
import org.smssecure.smssecure.database.GroupDatabase;
import org.smssecure.smssecure.database.MmsSmsColumns.Types;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.mms.AttachmentManager;
import org.smssecure.smssecure.mms.AttachmentTypeSelectorAdapter;
import org.smssecure.smssecure.mms.MediaTooLargeException;
import org.smssecure.smssecure.mms.MmsMediaConstraints;
import org.smssecure.smssecure.mms.OutgoingGroupMediaMessage;
import org.smssecure.smssecure.mms.OutgoingMediaMessage;
import org.smssecure.smssecure.mms.OutgoingMmsConnection;
import org.smssecure.smssecure.mms.OutgoingSecureMediaMessage;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.mms.SlideDeck;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.protocol.Tag;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingEndSessionMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.util.BitmapDecodingException;
import org.smssecure.smssecure.util.CharacterCalculator.CharacterState;
import org.smssecure.smssecure.util.Dialogs;
import org.smssecure.smssecure.util.DirectoryHelper;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.Emoji;
import org.smssecure.smssecure.util.GroupUtil;
import org.smssecure.smssecure.util.MemoryCleaner;
import org.smssecure.smssecure.util.SMSSecurePreferences;
import org.smssecure.smssecure.util.Util;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.util.guava.Optional;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

import static org.smssecure.smssecure.database.GroupDatabase.GroupRecord;
import static org.smssecure.smssecure.recipients.Recipient.RecipientModifiedListener;
import static org.whispersystems.textsecure.internal.push.PushMessageProtos.PushMessageContent.GroupContext;

/**
 * Activity for displaying a message thread, as well as
 * composing/sending a new message into that thread.
 *
 * @author Moxie Marlinspike
 *
 */
public class ConversationActivity extends PassphraseRequiredActionBarActivity
    implements ConversationFragment.ConversationFragmentListener,
               AttachmentManager.AttachmentListener,
               RecipientModifiedListener
{
  private static final String TAG = ConversationActivity.class.getSimpleName();

  public static final String RECIPIENTS_EXTRA        = "recipients";
  public static final String THREAD_ID_EXTRA         = "thread_id";
  public static final String MASTER_SECRET_EXTRA     = "master_secret";
  public static final String DRAFT_TEXT_EXTRA        = "draft_text";
  public static final String DRAFT_IMAGE_EXTRA       = "draft_image";
  public static final String DRAFT_AUDIO_EXTRA       = "draft_audio";
  public static final String DRAFT_VIDEO_EXTRA       = "draft_video";
  public static final String DISTRIBUTION_TYPE_EXTRA = "distribution_type";

  private static final int PICK_IMAGE        = 1;
  private static final int PICK_VIDEO        = 2;
  private static final int PICK_AUDIO        = 3;
  private static final int PICK_CONTACT_INFO = 4;
  private static final int GROUP_EDIT        = 5;

  private MasterSecret masterSecret;
  private EditText     composeText;
  private SendButton   sendButton;
  private TextView     charactersLeft;

  private AttachmentTypeSelectorAdapter attachmentAdapter;
  private AttachmentManager             attachmentManager;
  private BroadcastReceiver             securityUpdateReceiver;
  private BroadcastReceiver             groupUpdateReceiver;
  private Optional<EmojiDrawer>         emojiDrawer;
  private EmojiToggle                   emojiToggle;

  private Recipients recipients;
  private long       threadId;
  private int        distributionType;
  private boolean    isEncryptedConversation;
  private boolean    isMmsEnabled = true;
  private boolean    isCharactersLeftViewEnabled;

  private DynamicTheme        dynamicTheme        = new DynamicTheme();
  private DynamicLanguage     dynamicLanguage     = new DynamicLanguage();

  @Override
  protected void onCreate(Bundle state) {
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
    super.onCreate(state);

    setContentView(R.layout.conversation_activity);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    initializeReceivers();
    initializeViews();
    initializeResources();
    initializeDraft();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    if (!Util.isEmpty(composeText) || attachmentManager.isAttachmentPresent()) {
      saveDraft();
      attachmentManager.clear();
      composeText.setText("");
    }

    setIntent(intent);

    initializeResources();
    initializeDraft();

    ConversationFragment fragment = getFragment();

    if (fragment != null) {
      fragment.onNewIntent();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);

    initializeSecurity();
    initializeTitleBar();
    initializeEnabledCheck();
    initializeMmsEnabledCheck();
    initializeIme();
    calculateCharactersRemaining();

    MessageNotifier.setVisibleThread(threadId);
    markThreadAsRead();
  }

  @Override
  protected void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
    if (isFinishing()) overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_right);
  }

  @Override
  protected void onDestroy() {
    saveDraft();
    recipients.removeListener(this);
    unregisterReceiver(securityUpdateReceiver);
    unregisterReceiver(groupUpdateReceiver);
    MemoryCleaner.clean(masterSecret);
    super.onDestroy();
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, Intent data) {
    Log.w(TAG, "onActivityResult called: " + reqCode + ", " + resultCode + " , " + data);
    super.onActivityResult(reqCode, resultCode, data);

    if (data == null || resultCode != RESULT_OK) return;
    switch (reqCode) {
    case PICK_IMAGE:
      addAttachmentImage(data.getData());
      break;
    case PICK_VIDEO:
      addAttachmentVideo(data.getData());
      break;
    case PICK_AUDIO:
      addAttachmentAudio(data.getData());
      break;
    case PICK_CONTACT_INFO:
      addAttachmentContactInfo(data.getData());
      break;
    case GROUP_EDIT:
      this.recipients = RecipientFactory.getRecipientsForIds(this, data.getLongArrayExtra(GroupCreateActivity.GROUP_RECIPIENT_EXTRA), true);
      initializeTitleBar();
      break;
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    boolean pushRegistered = SMSSecurePreferences.isPushRegistered(this);

    if (isSingleConversation() && isEncryptedConversation) {
      inflater.inflate(R.menu.conversation_secure_identity, menu);
      inflater.inflate(R.menu.conversation_secure_sms, menu.findItem(R.id.menu_security).getSubMenu());
    } else if (isSingleConversation()) {
      if (!pushRegistered) inflater.inflate(R.menu.conversation_insecure_no_push, menu);
      inflater.inflate(R.menu.conversation_insecure, menu);
    }

    if (isSingleConversation()) {
      inflater.inflate(R.menu.conversation_callable, menu);
    } else if (isGroupConversation()) {
      inflater.inflate(R.menu.conversation_group_options, menu);

      if (!isPushGroupConversation()) {
        inflater.inflate(R.menu.conversation_mms_group_options, menu);
        if (distributionType == ThreadDatabase.DistributionTypes.BROADCAST) {
          menu.findItem(R.id.menu_distribution_broadcast).setChecked(true);
        } else {
          menu.findItem(R.id.menu_distribution_conversation).setChecked(true);
        }
      } else if (isActiveGroup()) {
        inflater.inflate(R.menu.conversation_push_group_options, menu);
      }
    }

    inflater.inflate(R.menu.conversation, menu);

    if (isSingleConversation() && getRecipients().getPrimaryRecipient().getContactUri() == null) {
      inflater.inflate(R.menu.conversation_add_to_contacts, menu);
    }

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
    case R.id.menu_call:                      handleDial(getRecipients().getPrimaryRecipient()); return true;
    case R.id.menu_delete_thread:             handleDeleteThread();                              return true;
    case R.id.menu_add_attachment:            handleAddAttachment();                             return true;
    case R.id.menu_view_media:                handleViewMedia();                                 return true;
    case R.id.menu_add_to_contacts:           handleAddToContacts();                             return true;
    case R.id.menu_start_secure_session:      handleStartSecureSession();                        return true;
    case R.id.menu_abort_session:             handleAbortSecureSession();                        return true;
    case R.id.menu_verify_identity:           handleVerifyIdentity();                            return true;
    case R.id.menu_group_recipients:          handleDisplayGroupRecipients();                    return true;
    case R.id.menu_distribution_broadcast:    handleDistributionBroadcastEnabled(item);          return true;
    case R.id.menu_distribution_conversation: handleDistributionConversationEnabled(item);       return true;
    case R.id.menu_edit_group:                handleEditPushGroup();                             return true;
    case R.id.menu_leave:                     handleLeavePushGroup();                            return true;
    case R.id.menu_invite:                    handleInviteLink();                                return true;
    case android.R.id.home:                   handleReturnToConversationList();                  return true;
    }

    return false;
  }

  @Override
  public void onBackPressed() {
    if (emojiDrawer.isPresent() && emojiDrawer.get().getVisibility() == View.VISIBLE) {
      emojiDrawer.get().setVisibility(View.GONE);
      emojiToggle.toggle();
    } else {
      super.onBackPressed();
    }
  }

  //////// Event Handlers

  private void handleReturnToConversationList() {
    Intent intent = new Intent(this, ConversationListActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.putExtra("master_secret", masterSecret);
    startActivity(intent);
    finish();
  }

  private void handleInviteLink() {
    composeText.setText(getString(R.string.ConversationActivity_install_smssecure, "https://github.com/SMSSecure/SMSSecure"));
  }

  private void handleVerifyIdentity() {
    Intent verifyIdentityIntent = new Intent(this, VerifyIdentityActivity.class);
    verifyIdentityIntent.putExtra("recipient", getRecipients().getPrimaryRecipient().getRecipientId());
    verifyIdentityIntent.putExtra("master_secret", masterSecret);
    startActivity(verifyIdentityIntent);
  }

  private void handleStartSecureSession() {
    if (getRecipients() == null) {
      Toast.makeText(this, getString(R.string.ConversationActivity_invalid_recipient),
                     Toast.LENGTH_LONG).show();
      return;
    }

    final Recipient recipient   = getRecipients().getPrimaryRecipient();
    String recipientName        = (recipient.getName() == null ? recipient.getNumber() : recipient.getName());
    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
    builder.setTitle(R.string.ConversationActivity_initiate_secure_session_question);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setCancelable(true);
    builder.setMessage(String.format(getString(R.string.ConversationActivity_initiate_secure_session_with_s_question),
                       recipientName));
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (!isEncryptedConversation){
          KeyExchangeInitiator.initiate(ConversationActivity.this, masterSecret, recipient, true);
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleAbortSecureSession() {
    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
    builder.setTitle(R.string.ConversationActivity_abort_secure_session_confirmation);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_abort_this_secure_session_question);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (isSingleConversation() && isEncryptedConversation) {
          final Context context = getApplicationContext();

          OutgoingEndSessionMessage endSessionMessage =
              new OutgoingEndSessionMessage(new OutgoingTextMessage(getRecipients(), "TERMINATE"));

          new AsyncTask<OutgoingEndSessionMessage, Void, Long>() {
            @Override
            protected Long doInBackground(OutgoingEndSessionMessage... messages) {
              return MessageSender.send(context, masterSecret, messages[0], threadId, false);
            }

            @Override
            protected void onPostExecute(Long result) {
              sendComplete(result);
            }
          }.execute(endSessionMessage);
        }
      }
    });
    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleViewMedia() {
    Intent intent = new Intent(this, MediaOverviewActivity.class);
    intent.putExtra(MediaOverviewActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(MediaOverviewActivity.RECIPIENT_EXTRA, recipients.getPrimaryRecipient().getRecipientId());
    intent.putExtra(MediaOverviewActivity.MASTER_SECRET_EXTRA, masterSecret);
    startActivity(intent);
  }

  private void handleLeavePushGroup() {
    if (getRecipients() == null) {
      Toast.makeText(this, getString(R.string.ConversationActivity_invalid_recipient),
                     Toast.LENGTH_LONG).show();
      return;
    }

    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
    builder.setTitle("");
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setCancelable(true);
    builder.setMessage("");
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        Context self = ConversationActivity.this;
        try {
          byte[]  groupId = GroupUtil.getDecodedId(getRecipients().getPrimaryRecipient().getNumber());
          DatabaseFactory.getGroupDatabase(self).setActive(groupId, false);

          GroupContext context = GroupContext.newBuilder()
                                             .setId(ByteString.copyFrom(groupId))
                                             .setType(GroupContext.Type.QUIT)
                                             .build();

          OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(self, getRecipients(),
                                                                                    context, null);
          MessageSender.send(self, masterSecret, outgoingMessage, threadId, false);
          DatabaseFactory.getGroupDatabase(self).remove(groupId, SMSSecurePreferences.getLocalNumber(self));
          initializeEnabledCheck();
        } catch (IOException e) {
          Log.w(TAG, e);
          Toast.makeText(self, "", Toast.LENGTH_LONG).show();
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleEditPushGroup() {
    Intent intent = new Intent(ConversationActivity.this, GroupCreateActivity.class);
    intent.putExtra(GroupCreateActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(GroupCreateActivity.GROUP_RECIPIENT_EXTRA, recipients.getPrimaryRecipient().getRecipientId());
    startActivityForResult(intent, GROUP_EDIT);
  }

  private void handleDistributionBroadcastEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.BROADCAST;
    item.setChecked(true);

    if (threadId != -1) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this)
                         .setDistributionType(threadId, ThreadDatabase.DistributionTypes.BROADCAST);
          return null;
        }
      }.execute();
    }
  }

  private void handleDistributionConversationEnabled(MenuItem item) {
    distributionType = ThreadDatabase.DistributionTypes.CONVERSATION;
    item.setChecked(true);

    if (threadId != -1) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this)
                         .setDistributionType(threadId, ThreadDatabase.DistributionTypes.CONVERSATION);
          return null;
        }
      }.execute();
    }
  }

  private void handleDial(Recipient recipient) {
    try {
      if (recipient == null) return;

      Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                              Uri.parse("tel:" + recipient.getNumber()));
      startActivity(dialIntent);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, anfe);
      Dialogs.showAlertDialog(this,
                           getString(R.string.ConversationActivity_calls_not_supported),
                           getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
    }
  }

  private void handleDisplayGroupRecipients() {
    new GroupMembersDialog(this, getRecipients()).display();
  }

  private void handleDeleteThread() {
    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
    builder.setTitle(R.string.ConversationActivity_delete_thread_confirmation);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_permanently_delete_this_conversation_question);
    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (threadId > 0) {
          DatabaseFactory.getThreadDatabase(ConversationActivity.this).deleteConversation(threadId);
          composeText.getText().clear();
          threadId = -1;
          finish();
        }
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleAddToContacts() {
    final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
    intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipients.getPrimaryRecipient().getNumber());
    intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
    startActivity(intent);
  }

  private void handleAddAttachment() {
    if (this.isMmsEnabled || DirectoryHelper.isPushDestination(this, getRecipients())) {
      AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
      builder.setIconAttribute(R.attr.conversation_attach_file);
      builder.setTitle(R.string.ConversationActivity_add_attachment);
      builder.setAdapter(attachmentAdapter, new AttachmentTypeListener());
      builder.show();
    } else {
      handleManualMmsRequired();
    }
  }

  private void handleManualMmsRequired() {
    Toast.makeText(this, R.string.MmsDownloader_error_reading_mms_settings, Toast.LENGTH_LONG).show();

    Intent intent = new Intent(this, PromptMmsActivity.class);
    intent.putExtras(getIntent().getExtras());
    startActivity(intent);
  }

  ///// Initializers

  private void initializeTitleBar() {
    final String    title;
    final String    subtitle;
    final Recipient recipient = getRecipients().getPrimaryRecipient();

    if (isSingleConversation()) {
      if (TextUtils.isEmpty(recipient.getName())) {
        title    = recipient.getNumber();
        subtitle = null;
      } else {
        title    = recipient.getName();
        subtitle = PhoneNumberUtils.formatNumber(recipient.getNumber());
      }
    } else if (isGroupConversation()) {
      if (isPushGroupConversation()) {
        final String groupName = recipient.getName();

        title    = (!TextUtils.isEmpty(groupName)) ? groupName : getString(R.string.ConversationActivity_unnamed_group);
        subtitle = null;
      } else {
        final int size = getRecipients().getRecipientsList().size();

        title    = getString(R.string.ConversationActivity_group_conversation);
        subtitle = (size == 1) ? getString(R.string.ConversationActivity_d_recipients_in_group_singular)
                               : String.format(getString(R.string.ConversationActivity_d_recipients_in_group), size);
      }
    } else {
      title    = getString(R.string.ConversationActivity_compose_message);
      subtitle = null;
    }

    getSupportActionBar().setTitle(title);
    getSupportActionBar().setSubtitle(subtitle);

    getWindow().getDecorView().setContentDescription(getString(R.string.conversation_activity__window_description, title));

    this.supportInvalidateOptionsMenu();
  }

  private void initializeDraft() {
    String draftText  = getIntent().getStringExtra(DRAFT_TEXT_EXTRA);
    Uri    draftImage = getIntent().getParcelableExtra(DRAFT_IMAGE_EXTRA);
    Uri    draftAudio = getIntent().getParcelableExtra(DRAFT_AUDIO_EXTRA);
    Uri    draftVideo = getIntent().getParcelableExtra(DRAFT_VIDEO_EXTRA);

    if (draftText != null)  composeText.setText(draftText);
    if (draftImage != null) addAttachmentImage(draftImage);
    if (draftAudio != null) addAttachmentAudio(draftAudio);
    if (draftVideo != null) addAttachmentVideo(draftVideo);

    if (draftText == null && draftImage == null && draftAudio == null && draftVideo == null) {
      initializeDraftFromDatabase();
    }
  }

  private void initializeEnabledCheck() {
    boolean enabled = !(isPushGroupConversation() && !isActiveGroup());
    composeText.setEnabled(enabled);
    sendButton.setEnabled(enabled);
  }

  private void initializeDraftFromDatabase() {
    new AsyncTask<Void, Void, List<Draft>>() {
      @Override
      protected List<Draft> doInBackground(Void... params) {
        MasterCipher masterCipher   = new MasterCipher(masterSecret);
        DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(ConversationActivity.this);
        List<Draft> results         = draftDatabase.getDrafts(masterCipher, threadId);

        draftDatabase.clearDrafts(threadId);

        return results;
      }

      @Override
      protected void onPostExecute(List<Draft> drafts) {
        boolean nativeEmojiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        Context context              = ConversationActivity.this;

        for (Draft draft : drafts) {
          if (draft.getType().equals(Draft.TEXT) && !nativeEmojiSupported) {
            composeText.setText(Emoji.getInstance(context).emojify(draft.getValue(),
                                                                   new Emoji.InvalidatingPageLoadedListener(composeText)),
                                TextView.BufferType.SPANNABLE);
          } else if (draft.getType().equals(Draft.TEXT)) {
            composeText.setText(draft.getValue());
          } else if (draft.getType().equals(Draft.IMAGE)) {
            addAttachmentImage(Uri.parse(draft.getValue()));
          } else if (draft.getType().equals(Draft.AUDIO)) {
            addAttachmentAudio(Uri.parse(draft.getValue()));
          } else if (draft.getType().equals(Draft.VIDEO)) {
            addAttachmentVideo(Uri.parse(draft.getValue()));
          }
        }
      }
    }.execute();
  }

  private void initializeSecurity() {
    Recipient    primaryRecipient       = getRecipients() == null ? null : getRecipients().getPrimaryRecipient();
    boolean      isPushDestination      = DirectoryHelper.isPushDestination(this, getRecipients());
    boolean      isSecureSmsAllowed     = (!isPushDestination || DirectoryHelper.isSmsFallbackAllowed(this, getRecipients()));
    boolean      isSecureSmsDestination = isSecureSmsAllowed     &&
                                          isSingleConversation() &&
                                          SessionUtil.hasSession(this, masterSecret, primaryRecipient);

    if (isPushDestination || isSecureSmsDestination) {
      this.isEncryptedConversation = true;
    } else {
      this.isEncryptedConversation = false;
    }

    sendButton.initializeAvailableTransports(!recipients.isSingleRecipient() || attachmentManager.isAttachmentPresent());
    if (!isPushDestination           ) sendButton.disableTransport("textsecure");
    if (!isSecureSmsDestination      ) sendButton.disableTransport("secure_sms");
    if (recipients.isGroupRecipient()) sendButton.disableTransport("insecure_sms");

    if (isPushDestination) {
      sendButton.setDefaultTransport("textsecure");
    } else if (isSecureSmsDestination) {
      sendButton.setDefaultTransport("secure_sms");
    } else {
      sendButton.setDefaultTransport("insecure_sms");
    }

    calculateCharactersRemaining();
  }

  private void initializeMmsEnabledCheck() {
    new AsyncTask<Void, Void, Boolean>() {
      @Override
      protected Boolean doInBackground(Void... params) {
        return OutgoingMmsConnection.isConnectionPossible(ConversationActivity.this);
      }

      @Override
      protected void onPostExecute(Boolean isMmsEnabled) {
        ConversationActivity.this.isMmsEnabled = isMmsEnabled;
      }
    }.execute();
  }

  private void initializeIme() {
    if (SMSSecurePreferences.isEnterImeKeyEnabled(this)) {
      composeText.setInputType(composeText.getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
    } else {
      composeText.setInputType(composeText.getInputType() | (InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
    }
  }

  private void initializeViews() {
    sendButton     = (SendButton) findViewById(R.id.send_button);
    composeText    = (EditText) findViewById(R.id.embedded_text_editor);
    charactersLeft = (TextView) findViewById(R.id.space_left);
    emojiDrawer    = Optional.absent();
    emojiToggle    = (EmojiToggle) findViewById(R.id.emoji_toggle);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      emojiToggle.setVisibility(View.GONE);
    }

    attachmentAdapter = new AttachmentTypeSelectorAdapter(this);
    attachmentManager = new AttachmentManager(this, this);

    SendButtonListener        sendButtonListener        = new SendButtonListener();
    ComposeKeyPressedListener composeKeyPressedListener = new ComposeKeyPressedListener();

    sendButton.setOnClickListener(sendButtonListener);
    sendButton.setEnabled(true);
    sendButton.setComposeTextView(composeText);
    sendButton.addOnTransportChangedListener(new OnTransportChangedListener() {
      @Override
      public void onChange(TransportOption newTransport) {
        calculateCharactersRemaining();
      }
    });

    composeText.setOnKeyListener(composeKeyPressedListener);
    composeText.addTextChangedListener(composeKeyPressedListener);
    composeText.setOnEditorActionListener(sendButtonListener);
    composeText.setOnClickListener(composeKeyPressedListener);
    composeText.setOnFocusChangeListener(composeKeyPressedListener);
    emojiToggle.setOnClickListener(new EmojiToggleListener());
  }

  private EmojiDrawer initializeEmojiDrawer() {
    EmojiDrawer emojiDrawer = (EmojiDrawer)((ViewStub)findViewById(R.id.emoji_drawer_stub)).inflate();
    emojiDrawer.setComposeEditText(composeText);
    return emojiDrawer;
  }

  private void initializeResources() {
    recipients       = RecipientFactory.getRecipientsForIds(this, getIntent().getLongArrayExtra(RECIPIENTS_EXTRA), true);
    threadId         = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);
    distributionType = getIntent().getIntExtra(DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
    masterSecret     = getIntent().getParcelableExtra(MASTER_SECRET_EXTRA);

    recipients.addListener(this);
  }

  @Override
  public void onModified(Recipient recipient) {
    initializeTitleBar();
  }

  private void initializeReceivers() {
    securityUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (intent.getLongExtra("thread_id", -1) == -1)
          return;

        if (intent.getLongExtra("thread_id", -1) == threadId) {
          initializeSecurity();
          initializeTitleBar();
          calculateCharactersRemaining();
        }
      }
    };

    groupUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.w("ConversationActivity", "Group update received...");
        if (recipients != null) {
          long[] ids = recipients.getIds();
          Log.w("ConversationActivity", "Looking up new recipients...");
          recipients = RecipientFactory.getRecipientsForIds(context, ids, false);
          initializeTitleBar();
        }
      }
    };

    registerReceiver(securityUpdateReceiver,
                     new IntentFilter(SecurityEvent.SECURITY_UPDATE_EVENT),
                     KeyCachingService.KEY_PERMISSION, null);

    registerReceiver(groupUpdateReceiver,
                     new IntentFilter(GroupDatabase.DATABASE_UPDATE_ACTION));
  }

  //////// Helper Methods

  private void addAttachment(int type) {
    Log.w("ComposeMessageActivity", "Selected: " + type);
    switch (type) {
    case AttachmentTypeSelectorAdapter.ADD_IMAGE:
      AttachmentManager.selectImage(this, PICK_IMAGE); break;
    case AttachmentTypeSelectorAdapter.ADD_VIDEO:
      AttachmentManager.selectVideo(this, PICK_VIDEO); break;
    case AttachmentTypeSelectorAdapter.ADD_SOUND:
      AttachmentManager.selectAudio(this, PICK_AUDIO); break;
    case AttachmentTypeSelectorAdapter.ADD_CONTACT_INFO:
      AttachmentManager.selectContactInfo(this, PICK_CONTACT_INFO); break;
    }
  }

  private void addAttachmentImage(Uri imageUri) {
    try {
      attachmentManager.setImage(imageUri);
    } catch (IOException | BitmapDecodingException e) {
      Log.w(TAG, e);
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
    }
  }

  private void addAttachmentVideo(Uri videoUri) {
    try {
      attachmentManager.setVideo(videoUri);
    } catch (IOException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    } catch (MediaTooLargeException e) {
      attachmentManager.clear();

      Toast.makeText(this, getString(R.string.ConversationActivity_sorry_the_selected_video_exceeds_message_size_restrictions,
                                     (MmsMediaConstraints.MAX_MESSAGE_SIZE/1024)),
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    }
  }

  private void addAttachmentAudio(Uri audioUri) {
    try {
      attachmentManager.setAudio(audioUri);
    } catch (IOException e) {
      attachmentManager.clear();
      Toast.makeText(this, R.string.ConversationActivity_sorry_there_was_an_error_setting_your_attachment,
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    } catch (MediaTooLargeException e) {
      attachmentManager.clear();
      Toast.makeText(this, getString(R.string.ConversationActivity_sorry_the_selected_audio_exceeds_message_size_restrictions,
                                     (MmsMediaConstraints.MAX_MESSAGE_SIZE/1024)),
                     Toast.LENGTH_LONG).show();
      Log.w("ComposeMessageActivity", e);
    }
  }

  private void addAttachmentContactInfo(Uri contactUri) {
    ContactAccessor contactDataList = ContactAccessor.getInstance();
    ContactData contactData = contactDataList.getContactData(this, contactUri);

    if      (contactData.numbers.size() == 1) composeText.append(contactData.numbers.get(0).number);
    else if (contactData.numbers.size() > 1)  selectContactInfo(contactData);
  }

  private void selectContactInfo(ContactData contactData) {
    final CharSequence[] numbers     = new CharSequence[contactData.numbers.size()];
    final CharSequence[] numberItems = new CharSequence[contactData.numbers.size()];

    for (int i = 0; i < contactData.numbers.size(); i++) {
      numbers[i]     = contactData.numbers.get(i).number;
      numberItems[i] = contactData.numbers.get(i).type + ": " + contactData.numbers.get(i).number;
    }

    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(this);
    builder.setIconAttribute(R.attr.conversation_attach_contact_info);
    builder.setTitle(R.string.ConversationActivity_select_contact_info);

    builder.setItems(numberItems, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        composeText.append(numbers[which]);
      }
    });
    builder.show();
  }

  private Drafts getDraftsForCurrentState() {
    Drafts drafts = new Drafts();

    if (!Util.isEmpty(composeText)) {
      drafts.add(new Draft(Draft.TEXT, composeText.getText().toString()));
    }

    for (Slide slide : attachmentManager.getSlideDeck().getSlides()) {
      if      (slide.hasAudio()) drafts.add(new Draft(Draft.AUDIO, slide.getUri().toString()));
      else if (slide.hasVideo()) drafts.add(new Draft(Draft.VIDEO, slide.getUri().toString()));
      else if (slide.hasImage()) drafts.add(new Draft(Draft.IMAGE, slide.getUri().toString()));
    }

    return drafts;
  }

  private void saveDraft() {
    if (this.recipients == null || this.recipients.isEmpty())
      return;

    final Drafts       drafts               = getDraftsForCurrentState();
    final long         thisThreadId         = this.threadId;
    final MasterSecret thisMasterSecret     = this.masterSecret.parcelClone();
    final int          thisDistributionType = this.distributionType;

    new AsyncTask<Long, Void, Void>() {
      @Override
      protected Void doInBackground(Long... params) {
        ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(ConversationActivity.this);
        DraftDatabase  draftDatabase  = DatabaseFactory.getDraftDatabase(ConversationActivity.this);
        long           threadId       = params[0];

        if (drafts.size() > 0) {
          if (threadId == -1) threadId = threadDatabase.getThreadIdFor(getRecipients(), thisDistributionType);

          draftDatabase.insertDrafts(new MasterCipher(thisMasterSecret), threadId, drafts);
          threadDatabase.updateSnippet(threadId, drafts.getSnippet(ConversationActivity.this), Types.BASE_DRAFT_TYPE);
        } else if (threadId > 0) {
          threadDatabase.update(threadId);
        }

        MemoryCleaner.clean(thisMasterSecret);
        return null;
      }
    }.execute(thisThreadId);
  }

  private void calculateCharactersRemaining() {
    int            charactersSpent = composeText.getText().toString().length();
    TransportOption transportOption = sendButton.getSelectedTransport();

    if (transportOption != null) {
      CharacterState characterState = transportOption.calculateCharacters(charactersSpent);

      if (characterState.charactersRemaining <= 15 || characterState.messagesSpent > 1) {
        charactersLeft.setText(characterState.charactersRemaining + "/" + characterState.maxMessageSize
                                   + " (" + characterState.messagesSpent + ")");
        charactersLeft.setVisibility(View.VISIBLE);
      } else {
        charactersLeft.setVisibility(View.GONE);
      }
    }
  }

  private boolean isExistingConversation() {
    return this.recipients != null && this.threadId != -1;
  }

  private boolean isSingleConversation() {
    return getRecipients() != null && getRecipients().isSingleRecipient() && !getRecipients().isGroupRecipient();
  }

  private boolean isActiveGroup() {
    if (!isGroupConversation()) return false;

    try {
      byte[]      groupId = GroupUtil.getDecodedId(getRecipients().getPrimaryRecipient().getNumber());
      GroupRecord record  = DatabaseFactory.getGroupDatabase(this).getGroup(groupId);

      return record != null && record.isActive();
    } catch (IOException e) {
      Log.w("ConversationActivity", e);
      return false;
    }
  }

  private boolean isGroupConversation() {
    return getRecipients() != null &&
        (!getRecipients().isSingleRecipient() || getRecipients().isGroupRecipient());
  }

  private boolean isPushGroupConversation() {
    return getRecipients() != null && getRecipients().isGroupRecipient();
  }

  private Recipients getRecipients() {
    return this.recipients;
  }

  private String getMessage() throws InvalidMessageException {
    String rawText = composeText.getText().toString();

    if (rawText.length() < 1 && !attachmentManager.isAttachmentPresent())
      throw new InvalidMessageException(getString(R.string.ConversationActivity_message_is_empty_exclamation));

    if (!isEncryptedConversation && Tag.isTaggable(rawText))
      rawText = Tag.getTaggedMessage(rawText);

    return rawText;
  }

  private void markThreadAsRead() {
    new AsyncTask<Long, Void, Void>() {
      @Override
      protected Void doInBackground(Long... params) {
        DatabaseFactory.getThreadDatabase(ConversationActivity.this).setRead(params[0]);
        MessageNotifier.updateNotification(ConversationActivity.this, masterSecret);
        return null;
      }
    }.execute(threadId);
  }

  private void sendComplete(long threadId) {
    boolean refreshFragment = (threadId != this.threadId);
    this.threadId = threadId;

    ConversationFragment fragment = getFragment();

    if (fragment == null) {
      return;
    }

    if (refreshFragment) {
      fragment.reload(recipients, threadId);

      initializeTitleBar();
      initializeSecurity();
    }

    fragment.scrollToBottom();
  }

  private ConversationFragment getFragment() {
    return (ConversationFragment)getSupportFragmentManager().findFragmentById(R.id.fragment_content);
  }

  private void sendMessage() {
    try {
      final Recipients recipients = getRecipients();

      if (recipients == null) {
        throw new RecipientFormattingException("Badly formatted");
      }

      if ((!recipients.isSingleRecipient() || recipients.isEmailRecipient()) && !isMmsEnabled) {
        handleManualMmsRequired();
      } else if (attachmentManager.isAttachmentPresent() || !recipients.isSingleRecipient() || recipients.isGroupRecipient() || recipients.isEmailRecipient()) {
        sendMediaMessage(sendButton.getSelectedTransport().isPlaintext(), sendButton.getSelectedTransport().isSms());
      } else {
        sendTextMessage(sendButton.getSelectedTransport().isPlaintext(), sendButton.getSelectedTransport().isSms());
      }
    } catch (RecipientFormattingException ex) {
      Toast.makeText(ConversationActivity.this,
                     R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation,
                     Toast.LENGTH_LONG).show();
      Log.w(TAG, ex);
    } catch (InvalidMessageException ex) {
      Toast.makeText(ConversationActivity.this, R.string.ConversationActivity_message_is_empty_exclamation,
                     Toast.LENGTH_SHORT).show();
      Log.w(TAG, ex);
    }
  }

  private void sendMediaMessage(boolean forcePlaintext, final boolean forceSms)
      throws InvalidMessageException
  {
    final Context context = getApplicationContext();
    SlideDeck slideDeck;

    if (attachmentManager.isAttachmentPresent()) slideDeck = new SlideDeck(attachmentManager.getSlideDeck());
    else                                         slideDeck = new SlideDeck();

    OutgoingMediaMessage outgoingMessage = new OutgoingMediaMessage(this, recipients, slideDeck,
                                                                    getMessage(), distributionType);

    if (isEncryptedConversation && !forcePlaintext) {
      outgoingMessage = new OutgoingSecureMediaMessage(outgoingMessage);
    }

    attachmentManager.clear();
    composeText.setText("");

    new AsyncTask<OutgoingMediaMessage, Void, Long>() {
      @Override
      protected Long doInBackground(OutgoingMediaMessage... messages) {
        return MessageSender.send(context, masterSecret, messages[0], threadId, forceSms);
      }

      @Override
      protected void onPostExecute(Long result) {
        sendComplete(result);
      }
    }.execute(outgoingMessage);
  }

  private void sendTextMessage(boolean forcePlaintext, final boolean forceSms)
      throws InvalidMessageException
  {
    final Context context = getApplicationContext();
    OutgoingTextMessage message;

    if (isEncryptedConversation && !forcePlaintext) {
      message = new OutgoingEncryptedMessage(recipients, getMessage());
    } else {
      message = new OutgoingTextMessage(recipients, getMessage());
    }

    this.composeText.setText("");

    new AsyncTask<OutgoingTextMessage, Void, Long>() {
      @Override
      protected Long doInBackground(OutgoingTextMessage... messages) {
        return MessageSender.send(context, masterSecret, messages[0], threadId, forceSms);
      }

      @Override
      protected void onPostExecute(Long result) {
        sendComplete(result);
      }
    }.execute(message);
  }


  // Listeners

  private class AttachmentTypeListener implements DialogInterface.OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      addAttachment(attachmentAdapter.buttonToCommand(which));
      dialog.dismiss();
    }
  }

  private class EmojiToggleListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      InputMethodManager input = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);

      if (emojiDrawer.isPresent() && emojiDrawer.get().isOpen()) {
        input.showSoftInput(composeText, 0);
        emojiDrawer.get().hide();
      } else {
        if (!emojiDrawer.isPresent()) {
          emojiDrawer = Optional.of(initializeEmojiDrawer());
        }
        input.hideSoftInputFromWindow(composeText.getWindowToken(), 0);

        emojiDrawer.get().show();
      }
    }
  }

  private class SendButtonListener implements OnClickListener, TextView.OnEditorActionListener {
    @Override
    public void onClick(View v) {
      sendMessage();
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendButton.performClick();
        composeText.clearFocus();
        return true;
      }
      return false;
    }
  }

  private class AddContactButtonListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
      intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipients.getPrimaryRecipient().getNumber());
      intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
      startActivity(intent);
    }
  }

  private class ComposeKeyPressedListener implements OnKeyListener, OnClickListener, TextWatcher, OnFocusChangeListener {
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
          if (SMSSecurePreferences.isEnterSendsEnabled(ConversationActivity.this)) {
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public void onClick(View v) {
      if (emojiDrawer.isPresent() && emojiDrawer.get().isOpen()) {
        emojiToggle.performClick();
      }
    }

    @Override
    public void afterTextChanged(Editable s) {
      calculateCharactersRemaining();
    }
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count,int after) {}
    @Override
    public void onTextChanged(CharSequence s, int start, int before,int count) {}

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
      if (hasFocus && emojiDrawer.isPresent() && emojiDrawer.get().isOpen()) {
        emojiToggle.performClick();
      }
    }
  }

  @Override
  public void setComposeText(String text) {
    this.composeText.setText(text);
  }

  @Override
  public void onAttachmentChanged() {
    initializeSecurity();
  }
}
