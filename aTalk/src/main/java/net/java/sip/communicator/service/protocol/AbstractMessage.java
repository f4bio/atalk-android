/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.service.protocol;

import org.atalk.android.gui.chat.ChatMessage;

import java.util.Objects;

/**
 * Represents a default implementation of {@link Message} in order to make it easier for
 * implementers to provide complete solutions while focusing on implementation-specific details.
 *
 * @author Lubomir Marinov
 * @author Eng Chong Meng
 */
public abstract class AbstractMessage implements Message
{
    private String mContent;
    private final int mEncType;
    private final int mEncryption;
    private final int mMimeType;
    private final boolean mRemoteOnly;
    private String mMessageUID;

    private int mReceiptStatus;
    private String mServerMessageId;
    private String mRemoteMessageId;

    /**
     * The content of this message, in raw bytes according to the encoding.
     */
    private byte[] rawData;

    private final String mSubject;

    /**
     * @param content the text content of the message.
     * @param encType contains both mime and encryption types @see ChatMessage.ENC_TYPE definition
     * @param subject the subject of the message or null for empty.
     */
    protected AbstractMessage(String content, int encType, String subject, String messageUID)
    {
        this(content, encType, subject, messageUID, ChatMessage.MESSAGE_DELIVERY_NONE, null, null);
    }

    /**
     * @param content the text content of the message.
     * @param encType contains both mime and encryption types @see ChatMessage.ENC_TYPE definition and other flags
     * @param subject the subject of the message or null for empty.
     * @param messageUID @see net.java.sip.communicator.service.protocol.Message#getMessageUID()
     */
    protected AbstractMessage(String content, int encType, String subject, String messageUID, int receiptStatus,
            String serverMessageId, String remoteMessageId)
    {
        mEncType = encType;
        mMimeType = encType & ENCODE_MIME_MASK;
        mEncryption = encType & ENCRYPTION_MASK;
        mRemoteOnly = (encType & FLAG_MODE_MASK) != 0;
        mSubject = subject;

        setContent(content);
        mMessageUID = messageUID == null ? createMessageUID() : messageUID;

        mReceiptStatus = receiptStatus;
        mServerMessageId = serverMessageId;
        mRemoteMessageId = remoteMessageId;
    }

    private String createMessageUID()
    {
        return System.currentTimeMillis() + String.valueOf(hashCode());
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.sip.communicator.service.protocol.Message#getMimeType()
     */
    public int getMimeType()
    {
        return mMimeType;
    }

    /**
     * Returns the content of this message if representable in text form or null if this message
     * does not contain text data.
     *
     * The implementation is final because it caches the raw data of the content.
     *
     * @return a String containing the content of this message or null if the message does not
     * contain data representable in text form.
     */
    public final String getContent()
    {
        return mContent;
    }

    /*
     * @return the Encryption Type for the message
     */
    public int getEncryptionType()
    {
        return mEncryption;
    }

    /**
     * @return the encType info
     */
    public int getEncType()
    {
        return mEncType;
    }

    /*
     * @return the Encryption Type for the message
     */
    public int getReceiptStatus()
    {
        return mReceiptStatus;
    }

    public void setReceiptStatus(int status)
    {
        mReceiptStatus = status;
    }

    /**
     * Returns the server message Id of the message sent - for tracking delivery receipt
     *
     * @return the server message Id of the message sent.
     */
    public String getServerMsgId(){
        return mServerMessageId;
    }

    public void setServerMsgId(String serverMsgId){
        mServerMessageId = serverMsgId;
    }

    /**
     * Returns the remote message Id of the message received - for tracking delivery receipt
     *
     * @return the remote message Id of the message received.
     */
    public String getRemoteMsgId(){
        return mRemoteMessageId;
    }

    public void setRemoteMsgId(String remoteMsgId){
        mRemoteMessageId = remoteMsgId;
    }

    public boolean isRemoteOnly() {
        return mRemoteOnly;
    }
    /*
     * (non-Javadoc)
     *
     * @see net.java.sip.communicator.service.protocol.Message#getMessageUID()
     */
    public String getMessageUID()
    {
        return mMessageUID;
    }

    public void setMessageUID(String msgUid)
    {
        mMessageUID = msgUid;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.sip.communicator.service.protocol.Message#getRawData()
     */
    public byte[] getRawData()
    {
        if (rawData == null) {
            String content = getContent();
            rawData = content.getBytes();
        }
        return rawData;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.sip.communicator.service.protocol.Message#getSize()
     */
    public int getSize()
    {
        return getRawData().length;
    }

    /*
     * (non-Javadoc)
     *
     * @see net.java.sip.communicator.service.protocol.Message#getSubject()
     */
    public String getSubject()
    {
        return mSubject;
    }

    protected void setContent(String content)
    {
        if (!equals(mContent, content)) {
            mContent = content;
            rawData = null;
        }
    }

    private static boolean equals(String a, String b)
    {
        return Objects.equals(a, b);
    }
}
