/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;

import org.atalk.android.gui.chat.ChatMessage;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.omemo.OmemoManager;
import org.jivesoftware.smackx.omemo.OmemoMessage;
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener;
import org.jivesoftware.smackx.xdata.Form;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.*;

import timber.log.Timber;

import static net.java.sip.communicator.service.protocol.Message.ENCODE_PLAIN;
import static net.java.sip.communicator.service.protocol.Message.ENCRYPTION_OMEMO;

/**
 * A jabber implementation of the multi user chat operation set.
 *
 * @author Emil Ivov
 * @author Yana Stamcheva
 * @author Eng Chong Meng
 */

public class OperationSetMultiUserChatJabberImpl extends AbstractOperationSetMultiUserChat
        implements SubscriptionListener, OmemoMucMessageListener
{
    /**
     * The currently valid Jabber protocol provider service implementation.
     */
    private final ProtocolProviderServiceJabberImpl jabberProvider;

    /**
     * A list of the rooms that are currently open by this account. Note that we have not
     * necessarily joined these rooms, we might have simply been searching through them.
     */
    private final Hashtable<BareJid, ChatRoomJabberImpl> chatRoomCache = new Hashtable<>();

    /**
     * A reference to the persistent presence operation set that we use to match incoming messages
     * to <tt>Contact</tt>s and vice versa.
     */
    private OperationSetPersistentPresenceJabberImpl opSetPersPresence;

    /**
     * A reference of the MultiUserChatManager
     */
    private MultiUserChatManager mMucMgr = null;

    private SmackInvitationListener mInvitationListener;

    private XMPPConnection mConnection = null;

    /**
     * Instantiates the user operation set with a currently valid instance of the Jabber protocol provider.
     * Note: the xmpp connection is not established yet.
     *
     * @param jabberProvider a currently valid instance of ProtocolProviderServiceJabberImpl.
     */

    OperationSetMultiUserChatJabberImpl(ProtocolProviderServiceJabberImpl jabberProvider)
    {
        this.jabberProvider = jabberProvider;

        // The registration listener that would get notified when the underlying Jabber provider gets registered.
        RegistrationStateListener providerRegListener = new RegistrationStateListener();
        jabberProvider.addRegistrationStateChangeListener(providerRegListener);
        opSetPersPresence = (OperationSetPersistentPresenceJabberImpl)
                jabberProvider.getOperationSet(OperationSetPersistentPresence.class);
        opSetPersPresence.addSubscriptionListener(this);
    }

    /**
     * Add SmackInvitationRejectionListener to <tt>MultiUserChat</tt> instance which will dispatch all rejection events.
     *
     * @param muc the smack MultiUserChat instance that we're going to wrap our chat room around.
     * @param chatRoom the associated chat room instance
     */
    public void addSmackInvitationRejectionListener(MultiUserChat muc, ChatRoom chatRoom)
    {
        muc.addInvitationRejectionListener(new SmackInvitationRejectionListener(chatRoom));
    }

    /**
     * Creates a room with the named <tt>roomName</tt> and according to the specified
     * <tt>roomProperties</tt> on the server that this protocol provider is currently connected to.
     *
     * @param roomName the name of the <tt>ChatRoom</tt> to create.
     * @param roomProperties properties specifying how the room should be created.
     * @return ChatRoom the chat room that we've just created.
     * @throws OperationFailedException if the room couldn't be created for some reason (e.g. room already exists; user
     * already joined to an existent room or user has no permissions to create a chat room).
     * @throws OperationNotSupportedException if chat room creation is not supported by this server
     */
    public ChatRoom createChatRoom(String roomName, Map<String, Object> roomProperties)
            throws OperationFailedException, OperationNotSupportedException
    {
        // first make sure we are connected and the server supports multiChat
        assertSupportedAndConnected();
        ChatRoom room = null;
        if (roomName == null) {
            // rooms using google servers needs special name in the form private-chat-UUID@groupchat.google.com
            if ((mConnection != null) && (mConnection.getHost().toLowerCase(Locale.US).contains("google"))) {
                roomName = "private-chat-" + UUID.randomUUID() + "@groupchat.google.com";
            }
            else
                roomName = "chatroom-" + StringUtils.randomString(4);
        }
        else {
            room = findRoom(roomName);
        }

        if ((room == null) && (mMucMgr != null)) {
            MultiUserChat muc;
            try {
                muc = mMucMgr.getMultiUserChat(getCanonicalRoomName(roomName));
                Resourcepart nick = Resourcepart.from(JabberActivator.getGlobalDisplayDetailsService()
                        .getDisplayName(jabberProvider));
                muc.create(nick);
            } catch (XMPPException | SmackException | XmppStringprepException | InterruptedException ex) {
                Timber.e(ex, "Failed to create chat room.");
                throw new OperationFailedException("Failed to create chat room",
                        OperationFailedException.GENERAL_ERROR, ex);
            }
            boolean isPrivate = false;
            if (roomProperties != null) {
                Object isPrivateObject = roomProperties.get("isPrivate");
                if (isPrivateObject != null) {
                    isPrivate = isPrivateObject.equals(true);
                }
            }
            try {
                Form form;
                if (isPrivate) {
                    Form initForm;
                    try {
                        initForm = muc.getConfigurationForm();
                    } catch (NoResponseException | NotConnectedException | InterruptedException e) {
                        throw new OperationFailedException("Could not get config form",
                                OperationFailedException.GENERAL_ERROR, e);
                    }
                    form = initForm.createAnswerForm();
                    for (FormField initField : initForm.getFields()) {
                        if ((initField == null) || (initField.getVariable() == null)
                                || (initField.getType() == FormField.Type.fixed)
                                || (initField.getType() == FormField.Type.hidden))
                            continue;

//                        FormField submitField = form.getField(initField.getVariable());
//                        if (submitField != null) {
//                            for (String fieldValue : initField.getValuesAsString()) {
//                                submitField.addValue(fieldValue);
//                            }
//                        }

                        FormField.Builder field = FormField.builder(initField.getVariable());
                        field.addValues(initField.getValues());
                        FormField submitField = field.build();
                    }
                    // cmeng - all the below fields are already in the default form.
                    String[] fields = {"muc#roomconfig_membersonly", "muc#roomconfig_allowinvites",
                            "muc#roomconfig_publicroom"};
                    Boolean[] values = {true, true, false};
                    for (int i = 0; i < fields.length; i++) {
                        try {
                            form.setAnswer(fields[i], values[i]);
                        } catch (IllegalArgumentException ignore) {
                            // ignore and continue
                            Timber.w("Exception in setAnswer for field: %s = %s", fields[i], values[i]);
                        }
                    }
                }
                else {
                    form = new Form(DataForm.Type.submit);
                }
                muc.sendConfigurationForm(form);
            } catch (XMPPException | NoResponseException | NotConnectedException |
                    InterruptedException e) {
                Timber.e(e, "Failed to send config form.");
            }
            room = createLocalChatRoomInstance(muc);
            // as we are creating the room we are the owner of it at least that's what MultiUserChat.create says
            room.setLocalUserRole(ChatRoomMemberRole.OWNER);
        }
        return room;
    }

    /**
     * Creates a <tt>ChatRoom</tt> from the specified smack <tt>MultiUserChat</tt>.
     *
     * @param muc the smack MultiUserChat instance that we're going to wrap our chat room around.
     * @return ChatRoom the chat room that we've just created.
     */
    private ChatRoom createLocalChatRoomInstance(MultiUserChat muc)
    {
        synchronized (chatRoomCache) {
            ChatRoomJabberImpl chatRoom = new ChatRoomJabberImpl(muc, jabberProvider);
            this.chatRoomCache.put(muc.getRoom(), chatRoom);

            // Add the contained in this class SmackInvitationRejectionListener which will dispatch
            // all rejection events to the ChatRoomInvitationRejectionListener.
            addSmackInvitationRejectionListener(muc, chatRoom);
            return chatRoom;
        }
    }

    /**
     * Returns a reference to a chatRoom named <tt>roomName</tt>. If the room doesn't exists in the
     * cache then creates it.
     *
     * @param roomName the name of the <tt>ChatRoom</tt> that we're looking for.
     * @return the <tt>ChatRoom</tt> named <tt>roomName</tt>
     * @throws OperationFailedException if an error occurs while trying to discover the room on the server.
     * @throws OperationNotSupportedException if the server does not support multi user chat
     */
    public synchronized ChatRoom findRoom(String roomName)
            throws OperationFailedException, OperationNotSupportedException
    {
        // make sure we are connected and multiChat is supported.
        assertSupportedAndConnected();
        EntityBareJid canonicalRoomName = getCanonicalRoomName(roomName);

        ChatRoomJabberImpl room = chatRoomCache.get(canonicalRoomName);
        if (room != null)
            return room;

        if (mMucMgr != null) {
            MultiUserChat muc = mMucMgr.getMultiUserChat(canonicalRoomName);
            room = new ChatRoomJabberImpl(muc, jabberProvider);
            chatRoomCache.put(canonicalRoomName, room);
        }
        return room;
    }

    /**
     * Returns a list of the chat rooms that we have joined and are currently active in.
     *
     * @return a <tt>List</tt> of the rooms where the user has joined using a given connection.
     */
    public List<ChatRoom> getCurrentlyJoinedChatRooms()
    {
        synchronized (chatRoomCache) {
            List<ChatRoom> joinedRooms = new LinkedList<>(this.chatRoomCache.values());
            Iterator<ChatRoom> joinedRoomsIter = joinedRooms.iterator();

            while (joinedRoomsIter.hasNext()) {
                if (!joinedRoomsIter.next().isJoined())
                    joinedRoomsIter.remove();
            }
            return joinedRooms;
        }
    }

//    /**
//     * Returns a list of the names of all chat rooms that <tt>contact</tt> is currently a member of.
//     *
//     * @param contact
//     * 		the contact whose current ChatRooms we will be querying.
//     * @return a list of <tt>String</tt> indicating the names of the chat rooms that
//     * <tt>contact</tt> has joined and is currently active in.
//     * @throws OperationFailedException
//     * 		if an error occurs while trying to discover the room on the server.
//     * @throws OperationNotSupportedException
//     * 		if the server does not support multi user chat
//     */
//    /* this method is not used */
//	public List getCurrentlyJoinedChatRooms(Contact contact)
//			throws OperationFailedException, OperationNotSupportedException
//	{
//		assertSupportedAndConnected();
//		Iterator joinedRoomsIter = MultiUserChat.getJoinedRooms(getXmppConnection(), contact.getAddress());
//		List joinedRoomsForContact = new LinkedList();
//
//		while (joinedRoomsIter.hasNext()) {
//			MultiUserChat muc = (MultiUserChat) joinedRoomsIter.next();
//			joinedRoomsForContact.add(muc.getRoom());
//		}
//		return joinedRoomsForContact;
//	}

    /**
     * Returns the <tt>List</tt> of <tt>String</tt>s indicating chat rooms currently available on
     * the server that this protocol provider is connected to.
     *
     * @return a <tt>java.util.List</tt> of the name <tt>String</tt>s for chat rooms that are
     * currently available on the server that this protocol provider is connected to.
     * @throws OperationFailedException if we failed retrieving this list from the server.
     * @throws OperationNotSupportedException if the server does not support multi user chat
     */
    public List<EntityBareJid> getExistingChatRooms()
            throws OperationFailedException, OperationNotSupportedException
    {
        assertSupportedAndConnected();
        List<EntityBareJid> list = new LinkedList<>();

        // first retrieve all conference service names available on this server
        List<DomainBareJid> serviceNames;
        if (mMucMgr != null) {
            try {
                // serviceNames = MultiUserChat.getServiceNames(getXmppConnection()).iterator();
                serviceNames = mMucMgr.getMucServiceDomains();
            } catch (XMPPException | NoResponseException | NotConnectedException | InterruptedException ex) {
                throw new OperationFailedException("Failed to retrieve Jabber conference service names",
                        OperationFailedException.GENERAL_ERROR, ex);
            }

            // Now retrieve all hostedRooms available for each service name and add the room EntityBareJid
            // the list of room names we are returning
            if (serviceNames != null) {
                for (DomainBareJid serviceName : serviceNames) {
                    try {
                        Map<EntityBareJid, HostedRoom> hostedRooms = mMucMgr.getRoomsHostedBy(serviceName);
                        list.addAll(hostedRooms.keySet());
                    } catch (XMPPException | NoResponseException | NotConnectedException | IllegalArgumentException
                            | MultiUserChatException.NotAMucServiceException | InterruptedException ex) {
                        Timber.e("Failed to retrieve room for %s : %s", serviceName, ex.getMessage());
                    }
                }
            }
        }
        return list;
    }

    /**
     * Returns true if <tt>contact</tt> supports multi user chat sessions.
     *
     * @param contact reference to the contact whose support for chat rooms we are currently querying.
     * @return a boolean indicating whether <tt>contact</tt> supports chatRooms.
     */
    public boolean isMultiChatSupportedByContact(Contact contact)
    {
        return contact.getProtocolProvider().getOperationSet(OperationSetMultiUserChat.class) != null;
    }

    /**
     * Checks if the contact address is associated with private messaging contact or not.
     *
     * @return <tt>true</tt> if the contact address is associated with private messaging contact
     * and <tt>false</tt> if not.
     */
    public boolean isPrivateMessagingContact(String contactAddress)
    {
        try {
            Jid jid = JidCreate.from(contactAddress);
            return opSetPersPresence.isPrivateMessagingContact(jid);
        } catch (XmppStringprepException e) {
            Timber.e(e, "%s is not a valid JID", contactAddress);
            return false;
        }
    }

    /**
     * Informs the sender of an invitation that we decline their invitation.
     *
     * @param invitation the connection to use for sending the rejection.
     * @param rejectReason the reason to reject the given invitation
     */
    public void rejectInvitation(ChatRoomInvitation invitation, String rejectReason)
            throws OperationFailedException
    {
        if (mMucMgr != null) {
            try {
                mMucMgr.decline(JidCreate.entityBareFrom(invitation.getTargetChatRoom().getIdentifier()),
                        invitation.getInviter().asEntityBareJidIfPossible(), rejectReason);
            } catch (NotConnectedException | InterruptedException | XmppStringprepException e) {
                throw new OperationFailedException("Could not reject invitation",
                        OperationFailedException.GENERAL_ERROR, e);
            }
        }
    }

    /**
     * Makes sure that we are properly connected and that the server supports multi user chats.
     *
     * @throws OperationFailedException if the provider is not registered or the xmpp connection not connected.
     * @throws OperationNotSupportedException if the service is not supported by the server.
     */
    private void assertSupportedAndConnected()
            throws OperationFailedException, OperationNotSupportedException
    {
        // throw an exception if the provider is not registered or the xmpp connection not connected.
        if (!jabberProvider.isRegistered() || (mConnection == null) || !mConnection.isConnected()) {
            throw new OperationFailedException("Provider not connected to jabber server",
                    OperationFailedException.NETWORK_FAILURE);
        }
    }

    /**
     * In case <tt>roomName</tt> does not represent a complete room id, the method returns a canonical
     * chat room name in the following form: roomName@muc-servicename.jabserver.com. In case <tt>roomName</tt>
     * is already a canonical room name, the method simply returns it without changing it.
     *
     * @param roomName the name of the room that we'd like to "canonize".
     * @return the canonical name of the room (which might be equal to roomName in case it was
     * already in a canonical format).
     * @throws OperationFailedException if we fail retrieving the conference service name
     */
    private EntityBareJid getCanonicalRoomName(String roomName)
            throws OperationFailedException
    {
        try {
            return JidCreate.entityBareFrom(roomName);
        } catch (XmppStringprepException e) {
            // try to append to domain part of our own JID
        }

        List<DomainBareJid> serviceNames = null;
        try {
            if (mMucMgr != null)
                serviceNames = mMucMgr.getMucServiceDomains();
        } catch (XMPPException | NoResponseException | NotConnectedException | InterruptedException ex) {
            AccountID accountId = jabberProvider.getAccountID();
            String errMsg = "Failed to retrieve conference service name for user: "
                    + accountId.getUserID() + " on server: " + accountId.getService();
            Timber.e(ex, "%s", errMsg);
            throw new OperationFailedException(errMsg, OperationFailedException.GENERAL_ERROR, ex);
        }

        if ((serviceNames != null) && !serviceNames.isEmpty()) {
            try {
                return JidCreate.entityBareFrom(Localpart.from(roomName), serviceNames.get(0));
            } catch (XmppStringprepException e) {
                throw new OperationFailedException(roomName + " is not a valid JID local part",
                        OperationFailedException.GENERAL_ERROR, e
                );
            }
        }
        // hmmmm strange.. no service name returned. we should probably throw an exception
        throw new OperationFailedException("Failed to retrieve MultiUserChat service names.",
                OperationFailedException.GENERAL_ERROR);
    }

    /*
     * Returns a reference to the chat room named <tt>chatRoomName</tt> or null if the room hasn't been cached yet.
     *
     * @param chatRoomName the name of the room we're looking for.
     * @return the <tt>ChatRoomJabberImpl</tt> instance that has been cached for
     * <tt>chatRoomName</tt> or null if no such room has been cached so far.
     */
    public ChatRoomJabberImpl getChatRoom(BareJid chatRoomName)
    {
        return this.chatRoomCache.get(chatRoomName);
    }

    /**
     * Returns the list of currently joined chat rooms for <tt>chatRoomMember</tt>.
     *
     * @param chatRoomMember the member we're looking for
     * @return a list of all currently joined chat rooms
     * @throws OperationFailedException if the operation fails
     * @throws OperationNotSupportedException if the operation is not supported
     */
    public List<String> getCurrentlyJoinedChatRooms(ChatRoomMember chatRoomMember)
            throws OperationFailedException, OperationNotSupportedException
    {
        assertSupportedAndConnected();
        List<String> joinedRooms = new ArrayList<>();
        if (mMucMgr != null) {
            try {
                for (EntityBareJid joinedRoom
                        : mMucMgr.getJoinedRooms(JidCreate.entityFullFrom(chatRoomMember.getContactAddress()))) {
                    joinedRooms.add(joinedRoom.toString());
                }
            } catch (NoResponseException | XMPPErrorException | NotConnectedException
                    | XmppStringprepException | InterruptedException e) {
                throw new OperationFailedException("Could not get list of joined rooms",
                        OperationFailedException.GENERAL_ERROR, e);
            }
        }
        return joinedRooms;
    }

    /**
     * Delivers a <tt>ChatRoomInvitationReceivedEvent</tt> to all registered
     * <tt>ChatRoomInvitationListener</tt>s.
     *
     * @param targetChatRoom the room that invitation refers to
     * @param inviter the inviter that sent the invitation
     * @param reason the reason why the inviter sent the invitation
     * @param password the password to use when joining the room
     */
    public void fireInvitationEvent(ChatRoom targetChatRoom, EntityJid inviter, String reason, byte[] password)
    {
        ChatRoomInvitationJabberImpl invitation
                = new ChatRoomInvitationJabberImpl(targetChatRoom, inviter, reason, password);
        fireInvitationReceived(invitation);
    }

    /**
     * A listener that is fired anytime an invitation to join a MUC room is received.
     */
    private class SmackInvitationListener implements InvitationListener
    {
        /**
         * Called when the an invitation to join a MUC room is received.
         *
         * If the room is password-protected, the invitee will receive a password to use to join
         * the room. If the room is members-only, then the invitee may be added to the member list.
         *
         * @param conn the XMPPConnection that received the invitation.
         * @param muc the multi user chatRoom that invitation refers to.
         * @param inviter the inviter that sent the invitation. (e.g. crone1@shakespeare.lit).
         * @param reason the reason why the inviter sent the invitation.
         * @param password the password to use when joining the room.
         * @param message the message used by the inviter to send the invitation.
         */
        @Override
        public void invitationReceived(XMPPConnection conn, MultiUserChat muc,
                EntityJid inviter, String reason, String password, Message message, MUCUser.Invite invitation)
        {
            ChatRoomJabberImpl chatRoom;
            String room = muc.getRoom().toString();
            if (muc.isJoined()) {
                Timber.w("Decline invitation! Already in the chat Room: %s", room);
                return;
            }

            try {
                chatRoom = (ChatRoomJabberImpl) findRoom(room);
                if (password != null)
                    fireInvitationEvent(chatRoom, inviter, reason, password.getBytes());
                else
                    fireInvitationEvent(chatRoom, inviter, reason, null);
            } catch (OperationFailedException | OperationNotSupportedException e) {
                Timber.e(e, "Failed to find room with name: %s", room);
            }
        }
    }

    /**
     * A listener that is fired anytime an invitee declines or rejects an invitation.
     */
    private class SmackInvitationRejectionListener implements InvitationRejectionListener
    {
        /**
         * The chat room for this listener.
         */
        private ChatRoom chatRoom;

        /**
         * Creates an instance of <tt>SmackInvitationRejectionListener</tt> and passes to it the
         * chat room for which it will listen for rejection events.
         *
         * @param chatRoom chat room for which this instance will listen for rejection events
         */
        public SmackInvitationRejectionListener(ChatRoom chatRoom)
        {
            this.chatRoom = chatRoom;
        }

        /**
         * Called when the invitee declines the invitation.
         *
         * @param invitee the invitee that declined the invitation. (e.g. hecate@shakespeare.lit).
         * @param reason the reason why the invitee declined the invitation.
         */
        @Override
        public void invitationDeclined(EntityBareJid invitee, String reason, Message message, MUCUser.Decline rejection)
        {
            fireInvitationRejectedEvent(chatRoom, invitee, reason);
        }
    }

    /**
     * Our listener that will tell us when we're registered to Jabber and the smack
     * MultiUserChat is ready to accept us as a listener.
     */
    private class RegistrationStateListener implements RegistrationStateChangeListener
    {
        /**
         * The method is called by a ProtocolProvider implementation whenever a change in the
         * registration state of the corresponding provider had occurred.
         *
         * @param evt ProviderStatusChangeEvent the event describing the status change.
         */
        public void registrationStateChanged(RegistrationStateChangeEvent evt)
        {
            if (evt.getNewState() == RegistrationState.REGISTERED) {
                Timber.d("adding an Invitation listener to the smack muc");

                mConnection = jabberProvider.getConnection();
                mMucMgr = MultiUserChatManager.getInstanceFor(mConnection);

                mInvitationListener = new SmackInvitationListener();
                mMucMgr.addInvitationListener(mInvitationListener);
            }
            else if (evt.getNewState() == RegistrationState.UNREGISTERED
                    || evt.getNewState() == RegistrationState.CONNECTION_FAILED) {
                // clear cached chatRooms as there are no longer valid
                chatRoomCache.clear();
            }
            else if (evt.getNewState() == RegistrationState.UNREGISTERING) {
                if (mMucMgr != null) {
                    mMucMgr.removeInvitationListener(mInvitationListener);
                    mInvitationListener = null;
                }
                // lets check for joined rooms and leave them
                List<ChatRoom> joinedRooms = getCurrentlyJoinedChatRooms();
                for (ChatRoom room : joinedRooms) {
                    room.leave();
                }
            }
        }

    }

    /**
     * Updates corresponding chat room members when a contact has been modified in our contact list.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void contactModified(ContactPropertyChangeEvent evt)
    {
        Contact modifiedContact = evt.getSourceContact();
        this.updateChatRoomMembers(modifiedContact);
    }

    /**
     * Updates corresponding chat room members when a contact has been created in our contact list.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void subscriptionCreated(SubscriptionEvent evt)
    {
        Contact createdContact = evt.getSourceContact();
        this.updateChatRoomMembers(createdContact);
    }

    /**
     * Not interested in this event for our member update purposes.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void subscriptionFailed(SubscriptionEvent evt)
    {
    }

    /**
     * Not interested in this event for our member update purposes.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void subscriptionMoved(SubscriptionMovedEvent evt)
    {
    }

    /**
     * Updates corresponding chat room members when a contact has been removed from our contact list.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void subscriptionRemoved(SubscriptionEvent evt)
    {
    }

    /**
     * Not interested in this event for our member update purposes.
     *
     * @param evt the <tt>SubscriptionEvent</tt> that notified us
     */
    public void subscriptionResolved(SubscriptionEvent evt)
    {
    }

    /**
     * Finds all chat room members, which name corresponds to the name of the given contact and
     * updates their contact references.
     *
     * @param contact the contact we're looking correspondences for.
     */
    private void updateChatRoomMembers(Contact contact)
    {
        // ConcurrentModificationException happens during test
        synchronized (chatRoomCache) {
            for (ChatRoomJabberImpl chatRoom : chatRoomCache.values()) {
                Resourcepart nick;
                try {
                    nick = contact.getJid().getResourceOrThrow();
                } catch (IllegalStateException e) {
                    continue;
                }

                ChatRoomMemberJabberImpl member = chatRoom.findMemberForNickName(nick);
                if (member != null) {
                    member.setContact(contact);
                    member.setAvatar(contact.getImage());
                }
            }
        }
    }

    /**
     * Return XEP-0203 time-stamp of the message if present or current time;
     *
     * @param msg Message
     * @return the correct message timeStamp
     */
    private Date getTimeStamp(org.jivesoftware.smack.packet.Message msg)
    {
        Date timeStamp;
        DelayInformation delayInfo
                = msg.getExtension(DelayInformation.ELEMENT, DelayInformation.NAMESPACE);
        if (delayInfo != null) {
            timeStamp = delayInfo.getStamp();
        }
        else {
            timeStamp = new Date();
        }
        return timeStamp;
    }

    // =============== OMEMO message received =============== //

    public void registerOmemoMucListener(OmemoManager omemoManager)
    {
        omemoManager.addOmemoMucMessageListener(this);
    }

    public void unRegisterOmemoMucListener(OmemoManager omemoManager)
    {
        omemoManager.removeOmemoMucMessageListener(this);
    }

    /**
     * Gets called whenever an OMEMO message has been received in a MultiUserChat and successfully decrypted.
     *
     * @param muc MultiUserChat the message was sent in
     * @param stanza Original Stanza
     * @param decryptedOmemoMessage decrypted Omemo message
     */
    @Override
    public void onOmemoMucMessageReceived(MultiUserChat muc, Stanza stanza, OmemoMessage.Received
            decryptedOmemoMessage)
    {
        Message message = (Message) stanza;
        String decryptedBody = decryptedOmemoMessage.getBody();

        Date timeStamp = getTimeStamp(message);
        ChatRoomJabberImpl chatRoom = getChatRoom(muc.getRoom());
        EntityFullJid msgFrom = (EntityFullJid) message.getFrom();
        ChatRoomMemberJabberImpl member = chatRoom.findMemberFromParticipant(msgFrom);

        int encType = ENCRYPTION_OMEMO | ENCODE_PLAIN;
        net.java.sip.communicator.service.protocol.Message newMessage
                = new MessageJabberImpl(decryptedBody, encType, "", message.getStanzaId());
        newMessage.setRemoteMsgId(message.getStanzaId());
        ChatRoomMessageReceivedEvent msgReceivedEvt = new ChatRoomMessageReceivedEvent(
                chatRoom, member, timeStamp, newMessage, ChatMessage.MESSAGE_MUC_IN);
        chatRoom.fireMessageEvent(msgReceivedEvt);
    }
}
