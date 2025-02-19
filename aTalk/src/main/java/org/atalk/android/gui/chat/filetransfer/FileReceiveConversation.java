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
package org.atalk.android.gui.chat.filetransfer;

import android.os.AsyncTask;
import android.view.*;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.ConfigurationUtils;

import org.atalk.android.*;
import org.atalk.android.gui.AndroidGUIActivator;
import org.atalk.android.gui.chat.ChatFragment;
import org.atalk.persistance.FileBackend;
import org.jivesoftware.smack.util.StringUtils;

import java.io.File;
import java.util.Date;

import timber.log.Timber;

/**
 * The <tt>ReceiveFileConversationComponent</tt> is the component shown in the conversation area
 * of the chat window to display a incoming file transfer.
 *
 * @author Eng Chong Meng
 */
public class FileReceiveConversation extends FileTransferConversation
        implements ScFileTransferListener, FileTransferStatusListener
{
    private IncomingFileTransferRequest fileTransferRequest;
    private OperationSetFileTransfer fileTransferOpSet;
    private String mDate;
    private String mSendTo;

    public FileReceiveConversation()
    {
    }

    /**
     * Creates a <tt>ReceiveFileConversationComponent</tt>.
     *
     * @param cPanel the chat panel
     * @param opSet the <tt>OperationSetFileTransfer</tt>
     * @param request the <tt>IncomingFileTransferRequest</tt> associated with this component
     * @param date the date
     */
    // Constructor used by ChatFragment to start handle ReceiveFileTransferRequest
    public static FileReceiveConversation newInstance(ChatFragment cPanel, String sendTo,
            OperationSetFileTransfer opSet, IncomingFileTransferRequest request, final Date date)
    {
        FileReceiveConversation fragmentRFC = new FileReceiveConversation();
        fragmentRFC.mChatFragment = cPanel;
        fragmentRFC.mSendTo = sendTo;
        fragmentRFC.fileTransferOpSet = opSet;
        fragmentRFC.fileTransferRequest = request;
        fragmentRFC.mDate = date.toString();

        // need to enable ScFileTransferListener for ReceiveFileConversion reject/cancellation.
        fragmentRFC.fileTransferOpSet.addFileTransferListener(fragmentRFC);
        return fragmentRFC;
    }

    public View ReceiveFileConversionForm(LayoutInflater inflater, ChatFragment.MessageViewHolder msgViewHolder,
            ViewGroup container, int id, boolean init)
    {
        msgId = id;
        View convertView = inflateViewForFileTransfer(inflater, msgViewHolder, container, init);
        messageViewHolder.arrowDir.setImageResource(R.drawable.filexferarrowin);
        messageViewHolder.stickerView.setImageDrawable(null);

        messageViewHolder.titleLabel.setText(
                aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_REQUEST_RECEIVED, mDate, mSendTo));

        long downloadFileSize = fileTransferRequest.getFileSize();
        String fileName = getFileLabel(fileTransferRequest.getFileName(), downloadFileSize);
        messageViewHolder.fileLabel.setText(fileName);

		/* Must keep track of file transfer status as Android always request view redraw on
		listView scrolling, new message send or received */
        int status = getXferStatus();
        if (status == -1) {
            if (FileTransferConversation.FT_THUMBNAIL_ENABLE) {
                byte[] thumbnail = fileTransferRequest.getThumbnail();
                showThumbnail(thumbnail);
            }

            mXferFile = createFile(fileTransferRequest);
            messageViewHolder.acceptButton.setVisibility(View.VISIBLE);
            messageViewHolder.acceptButton.setOnClickListener(v -> {
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_PREPARING, mDate, mSendTo));
                messageViewHolder.acceptButton.setVisibility(View.GONE);
                messageViewHolder.rejectButton.setVisibility(View.GONE);

                // set the download for global display parameter
                mChatFragment.getChatListAdapter().setFileName(msgId, mXferFile);
                (new acceptFile(mXferFile)).execute();
            });

            boolean isAutoAccept = (downloadFileSize <= ConfigurationUtils.getAutoAcceptFileSize());
            if (isAutoAccept)
                messageViewHolder.acceptButton.performClick();

            messageViewHolder.rejectButton.setVisibility(View.VISIBLE);
            messageViewHolder.rejectButton.setOnClickListener(v -> {
                hideProgressRelatedComponents();
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_REFUSED, mDate));
                messageViewHolder.acceptButton.setVisibility(View.GONE);
                messageViewHolder.rejectButton.setVisibility(View.GONE);
                try {
                    fileTransferRequest.rejectFile();
                } catch (OperationFailedException e) {
                    Timber.e("Reject file exception: %s", e.getMessage());
                }
                // need to update status here as chatFragment statusListener is enabled for
                // fileTransfer and only after accept
                setXferStatus(FileTransferStatusChangeEvent.CANCELED);
            });
        }
        else {
            updateView(status, "");
        }
        return convertView;
    }

    /**
     * Handles file transfer status changes. Updates the interface to reflect the changes.
     */
    private void updateView(final int status, final String reason)
    {
        setEncState(mEncryption);

        boolean bgAlert = false;
        switch (status) {
            case FileTransferStatusChangeEvent.PREPARING:
                // hideProgressRelatedComponents();
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_PREPARING, mDate, mSendTo));
                break;

            case FileTransferStatusChangeEvent.IN_PROGRESS:
                  // cmeng: seems to only visible after the file transfer is completed.
//                if (!messageViewHolder.mProgressBar.isShown()) {
//                    messageViewHolder.mProgressBar.setVisibility(View.VISIBLE);
//                    messageViewHolder.mProgressBar.setMax((int) fileTransferRequest.getFileSize());
//                    // setFileTransfer(fileTransfer, fileTransferRequest.getFileSize());
//                }
                messageViewHolder.cancelButton.setVisibility(View.VISIBLE);
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_RECEIVING_FROM, mDate, mSendTo));
                mChatFragment.getChatPanel().setCacheRefresh(true);
                break;

            case FileTransferStatusChangeEvent.COMPLETED:
                if (mXferFile == null) { // Android view redraw happen
                    mXferFile = mChatFragment.getChatListAdapter().getFileName(msgId);
                }
                MyGlideApp.loadImage(messageViewHolder.stickerView, mXferFile, false);

                // set to full for progressBar and update file label
                long fileSize = mXferFile.length();
                onUploadProgress(fileSize, fileSize);
                messageViewHolder.fileLabel.setText(getFileLabel(mXferFile.getName(), fileSize));

                setCompletedDownloadFile(mChatFragment, mXferFile);
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_RECEIVE_COMPLETED, mDate, mSendTo));
                messageViewHolder.cancelButton.setVisibility(View.GONE);
                messageViewHolder.rejectButton.setVisibility(View.GONE);
                break;

            case FileTransferStatusChangeEvent.FAILED:
                // hideProgressRelatedComponents(); keep the status info for user view
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_RECEIVE_FAILED, mDate, mSendTo, reason));
                messageViewHolder.cancelButton.setVisibility(View.GONE);
                bgAlert = true;
                break;

            case FileTransferStatusChangeEvent.CANCELED:
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_CANCELED, mDate));
                messageViewHolder.cancelButton.setVisibility(View.GONE);
                bgAlert = true;
                break;

            case FileTransferStatusChangeEvent.REFUSED:
                // hideProgressRelatedComponents();
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_REFUSED, mDate));
                messageViewHolder.cancelButton.setVisibility(View.GONE);
                bgAlert = true;
                break;
        }
        if (bgAlert) {
            messageViewHolder.titleLabel.setTextColor(
                    AndroidGUIActivator.getResources().getColor("red"));
        }
    }

    /**
     * Handles status changes in file transfer.
     */
    public void statusChanged(FileTransferStatusChangeEvent event)
    {
        final FileTransfer fileTransfer = event.getFileTransfer();
        final int status = event.getNewStatus();
        final String reason = event.getReason();
        setXferStatus(status);

        // Event thread - Must execute in UiThread to Update UI information
        runOnUiThread(() -> {
            updateView(status, reason);
            if (status == FileTransferStatusChangeEvent.COMPLETED
                    || status == FileTransferStatusChangeEvent.CANCELED
                    || status == FileTransferStatusChangeEvent.FAILED
                    || status == FileTransferStatusChangeEvent.REFUSED) {
                // must do this in UI, otherwise the status is not being updated to FileRecord
                fileTransfer.removeStatusListener(FileReceiveConversation.this);
                // removeProgressListener();
            }
        });
    }

    /**
     * Creates the local file to download.
     *
     * @return the local created file to download.
     */
    private File createFile(IncomingFileTransferRequest fileTransferRequest)
    {
        String incomingFileName = fileTransferRequest.getFileName();
        String mimeType = fileTransferRequest.getMimeType();

        String downloadPath = FileBackend.MEDIA_DOCUMENT;
        if (incomingFileName.contains("voice-"))
            downloadPath = FileBackend.MEDIA_VOICE_RECEIVE;
        else if (!StringUtils.isNullOrEmpty(mimeType)) {
            downloadPath = FileBackend.MEDIA + File.separator + mimeType.split("/")[0];
        }

        File downloadDir = FileBackend.getaTalkStore(downloadPath);
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            Timber.e("Could not create the download directory: %s", downloadDir.getAbsolutePath());
        }

        mXferFile = new File(downloadDir, incomingFileName);
        // If a file with the given name already exists, add an index to the file name.
        int index = 0;
        int filenameLength = incomingFileName.lastIndexOf(".");
        if (filenameLength == -1) {
            filenameLength = incomingFileName.length();
        }
        while (mXferFile.exists()) {
            String newFileName = incomingFileName.substring(0, filenameLength) + "-"
                    + ++index + incomingFileName.substring(filenameLength);
            mXferFile = new File(downloadDir, newFileName);
        }

        // Change the file name to the name we would use on the local file system.
        if (!mXferFile.getName().equals(incomingFileName)) {
            String fileName = getFileLabel(mXferFile.getName(), fileTransferRequest.getFileSize());
            messageViewHolder.fileLabel.setText(fileName);
        }
        return mXferFile;
    }

    /**
     * Accepts the file in a new thread.
     */
    private class acceptFile extends AsyncTask<Void, Void, String>
    {
        private final File dFile;
        private FileTransfer fileTransfer;

        private acceptFile(File mFile)
        {
            this.dFile = mFile;
        }

        @Override
        public void onPreExecute()
        {
        }

        @Override
        protected String doInBackground(Void... params)
        {
            fileTransfer = fileTransferRequest.acceptFile(dFile);
            mChatFragment.addActiveFileTransfer(fileTransfer.getID(), fileTransfer, msgId);

            // Remove previously added listener (no further required), that notify for request cancellations if any.
            fileTransferOpSet.removeFileTransferListener(FileReceiveConversation.this);
            fileTransfer.addStatusListener(FileReceiveConversation.this);
            return "";
        }

        @Override
        protected void onPostExecute(String result)
        {
            if (fileTransfer != null) {
                setFileTransfer(fileTransfer, fileTransferRequest.getFileSize());
            }
        }
    }

    /**
     * Called when a <tt>FileTransferCreatedEvent</tt> has been received from sendFile.
     *
     * @param event the <tt>FileTransferCreatedEvent</tt> containing the newly received file transfer and
     * other details.
     */
    public void fileTransferCreated(FileTransferCreatedEvent event)
    {
    }

    /**
     * Called when an <tt>IncomingFileTransferRequest</tt> has been canceled from the contact who
     * sent it.
     *
     * @param event the <tt>FileTransferRequestEvent</tt> containing the request which was canceled.
     */
    public void fileTransferRequestCanceled(FileTransferRequestEvent event)
    {
        final IncomingFileTransferRequest request = event.getRequest();
        // Different thread - Must execute in UiThread to Update UI information
        runOnUiThread(() -> {
            if (request.equals(fileTransferRequest)) {
                messageViewHolder.acceptButton.setVisibility(View.GONE);
                messageViewHolder.rejectButton.setVisibility(View.GONE);
                fileTransferOpSet.removeFileTransferListener(FileReceiveConversation.this);
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_CANCELED, mDate));
            }
        });
    }

    /**
     * Called when a new <tt>IncomingFileTransferRequest</tt> has been received.
     *
     * @param event the <tt>FileTransferRequestEvent</tt> containing the newly received request and other
     * details.
     * @see FileTransferActivator#fileTransferRequestReceived(FileTransferRequestEvent)
     */
    public void fileTransferRequestReceived(FileTransferRequestEvent event)
    {
        // Event handled by FileTransferActivator - nothing to do here
    }

    /**
     * Called when an <tt>IncomingFileTransferRequest</tt> has been rejected.
     *
     * @param event the <tt>FileTransferRequestEvent</tt> containing the received request which was
     * rejected.
     */
    public void fileTransferRequestRejected(FileTransferRequestEvent event)
    {
        final IncomingFileTransferRequest request = event.getRequest();
        // Different thread - Must execute in UiThread to Update UI information
        runOnUiThread(() -> {
            if (request.equals(fileTransferRequest)) {
                messageViewHolder.acceptButton.setVisibility(View.GONE);
                messageViewHolder.rejectButton.setVisibility(View.GONE);
                fileTransferOpSet.removeFileTransferListener(FileReceiveConversation.this);

                hideProgressRelatedComponents();
                // delete created mXferFile???
                messageViewHolder.titleLabel.setText(
                        aTalkApp.getResString(R.string.xFile_FILE_TRANSFER_REFUSED, mDate));
            }
        });
    }

    /**
     * Returns the label to show on the progress bar.
     *
     * @param bytesString the bytes that have been transferred
     * @return the label to show on the progress bar
     */
    @Override
    protected String getProgressLabel(long bytesString)
    {
        return aTalkApp.getResString(R.string.service_gui_RECEIVED, bytesString);
    }
}
