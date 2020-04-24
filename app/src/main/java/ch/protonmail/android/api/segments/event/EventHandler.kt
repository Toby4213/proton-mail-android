/*
 * Copyright (c) 2020 Proton Technologies AG
 * 
 * This file is part of ProtonMail.
 * 
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.api.segments.event

import android.app.NotificationManager
import android.content.Context
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.interceptors.RetrofitTag
import ch.protonmail.android.api.models.*
import ch.protonmail.android.api.models.address.Address
import ch.protonmail.android.api.models.address.AddressKeyActivationWorker
import ch.protonmail.android.api.models.contacts.receive.ContactLabelFactory
import ch.protonmail.android.api.models.enumerations.MessageFlag
import ch.protonmail.android.api.models.messages.receive.*
import ch.protonmail.android.api.models.room.contacts.*
import ch.protonmail.android.api.models.room.messages.*
import ch.protonmail.android.api.models.room.notifications.Notification
import ch.protonmail.android.api.models.room.pendingActions.PendingActionsDao
import ch.protonmail.android.api.models.room.pendingActions.PendingActionsDatabase
import ch.protonmail.android.api.segments.message.MessageApiSpec
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.ProtonMailApplication
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.events.MessageCountsEvent
import ch.protonmail.android.events.RefreshDrawerEvent
import ch.protonmail.android.events.Status
import ch.protonmail.android.events.user.MailSettingsEvent
import ch.protonmail.android.events.user.UserSettingsEvent
import ch.protonmail.android.jobs.FetchContactsDataJob
import ch.protonmail.android.jobs.FetchContactsEmailsJob
import ch.protonmail.android.jobs.OnFirstLoginJob
import ch.protonmail.android.servers.notification.INotificationServer
import ch.protonmail.android.servers.notification.NotificationServer
import ch.protonmail.android.utils.AppUtil
import ch.protonmail.android.utils.Logger
import ch.protonmail.android.utils.MessageUtils
import ch.protonmail.android.utils.extensions.ifNullElse
import ch.protonmail.android.utils.extensions.removeFirst
import ch.protonmail.android.utils.extensions.replaceFirst
import com.birbit.android.jobqueue.JobManager
import com.google.gson.JsonSyntaxException
import java.util.*
import javax.inject.Inject
import kotlin.collections.set
import kotlin.math.max

// region constants
private const val TAG_EVENT_HANDLER = "EventHandler"
// endregion

enum class EventType(val eventType: Int) {
    DELETE(0),
    CREATE(1),
    UPDATE(2),
    UPDATE_FLAGS(3);

    companion object {
        fun fromInt(eventType: Int): EventType {
            return values().find {
                eventType == it.eventType
            } ?: DELETE
        }
    }
}

class EventHandler(
        private val messageApi: MessageApiSpec,
        private val databaseProvider: DatabaseProvider,
        private val userManager: UserManager,
        private val jobManager: JobManager,
        val username: String,
        private val notificationServer: NotificationServer
) {

    private val messageFactory: MessageFactory
    private val contactsDao by lazy { databaseProvider.provideContactsDao(username) }
    @Inject
    lateinit var messageDetailsRepository: MessageDetailsRepository

    init {
        ProtonMailApplication.getApplication().appComponent.inject(this)
        val attachmentFactory = AttachmentFactory()
        val messageSenderFactory = MessageSenderFactory()
        messageFactory = MessageFactory(attachmentFactory, messageSenderFactory)
        messageDetailsRepository.reloadDependenciesForUser(username)
    }

    fun handleRefreshContacts() {
        contactsDao.clearContactDataCache()
        contactsDao.clearContactEmailsLabelsJoin()
        contactsDao.clearContactEmailsCache()
        contactsDao.clearContactGroupsLabelsTable()
        jobManager.addJob(FetchContactsEmailsJob(0))
        jobManager.addJob(FetchContactsDataJob())
    }

    /**
     * We should only return after the data has been refreshed so the eventmanager knows we are in
     * the correct state. We can do api requests here, because our data already has been invalidated
     * anyway.
     */
    fun handleRefresh() {
        val messagesDao = databaseProvider.provideMessagesDao(username)
        messagesDao.clearMessagesCache()
        messagesDao.clearAttachmentsCache()
        messagesDao.clearLabelsCache()
        val countersDao = databaseProvider.provideCountersDao(username)
        countersDao.clearUnreadLocationsTable()
        countersDao.clearUnreadLabelsTable()
        countersDao.clearTotalLocationsTable()
        countersDao.clearTotalLabelsTable()
        // todo make this done sequentially, don't fire and forget.
        jobManager.addJobInBackground(OnFirstLoginJob(false, false))
    }

    private lateinit var response: EventResponse
    private val stagedMessages = HashMap<String, Message>()

    /**
     * Does all the preprocessing which does not change the database state
     */
    fun stage(response: EventResponse) {
        this.response = response
        stagedMessages.clear()
        val messages = response.messageUpdates
        if (messages != null) {
            stageMessagesUpdates(messages)
        }
    }

    private fun stageMessagesUpdates(events: List<EventResponse.MessageEventBody>) {
        val pendingActionsDao = databaseProvider.providePendingActionsDao(username)
        for (event in events) {
            val messageID = event.messageID
            val type = EventType.fromInt(event.type)

            if (type != EventType.UPDATE && type != EventType.UPDATE_FLAGS) {
                continue
            }

            if (checkPendingForSending(pendingActionsDao, messageID)) {
                continue
            }

            val newMessage = messageApi.messageDetail(messageID, RetrofitTag(username)).message
            stagedMessages[messageID] = newMessage
        }
    }

    fun write() {
        databaseProvider.provideContactsDatabase(username).runInTransaction {
            databaseProvider.provideMessagesDatabaseFactory(username).runInTransaction {
                val messagesDao = databaseProvider.provideMessagesDao(username)
                databaseProvider.providePendingActionsDatabase(username).runInTransaction {
                    val pendingActionsDao = databaseProvider.providePendingActionsDao(username)
                    unsafeWrite(contactsDao, messagesDao, pendingActionsDao)
                }
            }
        }

        //val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }



    private fun showNotification(user: User, message: Message) {
        val title = message.subject ?: "New message"
        val body = "You have received a new message"
        val messageId = message.messageId ?: return
        notificationServer.notifySingleNewEmail(
                userManager, user, message, messageId,
                body,
                title, true
        )
    }

    private fun eventMessageSortSelector(message: EventResponse.MessageEventBody): Int = message.type

    /**
     * NOTE we should not do api requests here because we are in a transaction
     */
    private fun unsafeWrite(contactsDao: ContactsDao, messagesDao: MessagesDao,
                            pendingActionsDao: PendingActionsDao) {

        val response = this.response
        val savedUser = userManager.getUser(username)

        if (response.usedSpace > 0) {
            savedUser.setAndSaveUsedSpace(response.usedSpace)
        }

        val messages = response.messageUpdates
        val contacts = response.contactUpdates
        val contactsEmails = response.contactEmailsUpdates

        val user = response.userUpdates

        val mailSettings = response.mailSettingsUpdates
        val userSettings = response.userSettingsUpdates
        val labels = response.labelUpdates
        val counts = response.messageCounts
        val addresses = response.addresses

        if (labels != null) {
            writeLabelsUpdates(messagesDao, contactsDao, labels)
        }
        if (messages != null) {
            messages.sortByDescending { eventMessageSortSelector(it) }
            writeMessagesUpdates(messagesDao, pendingActionsDao, messages, savedUser)
        }
        if (contacts != null) {
            writeContactsUpdates(contactsDao, contacts)
        }
        if (contactsEmails != null) {
            writeContactEmailsUpdates(contactsDao, contactsEmails)
        }
        if (mailSettings != null) {
            writeMailSettings(mailSettings)
        }
        if (userSettings != null) {
            writeUserSettings(userSettings)
        }

        if (user != null) {
            user.username = username
            if (addresses != null && addresses.size > 0) {
                writeAddressUpdates(addresses, ArrayList(savedUser.addresses!!), user)
            } else {
                user.setAddresses(ArrayList<Address>(savedUser.addresses!!))
            }
            user.setAddressIdEmail()
            user.notificationSetting = savedUser.notificationSetting
            userManager.user = user
            AppUtil.postEventOnUi(RefreshDrawerEvent())
        } else {
            if (addresses != null && addresses.size > 0) {
                writeAddressUpdates(addresses, savedUser.addresses, savedUser)
                userManager.user = savedUser
                AppUtil.postEventOnUi(RefreshDrawerEvent())
            }
        }
        if (counts != null) {
            writeUnreadUpdates(counts)
        }
    }


    private fun writeMailSettings(mSettings: MailSettings) {
        var mailSettings: MailSettings? = mSettings
        if (mailSettings == null) {
            mailSettings = MailSettings()
        }
        mailSettings.username = username
        mailSettings.showImages = mSettings.showImages
        mailSettings.autoSaveContacts = mSettings.autoSaveContacts
        mailSettings.sign = mSettings.sign
        mailSettings.pgpScheme = mSettings.pgpScheme
        mailSettings.setAttachPublicKey(if (mSettings.getAttachPublicKey()) 1 else 0)
        mailSettings.save()
        AppUtil.postEventOnUi(MailSettingsEvent(mailSettings))
    }

    private fun writeUserSettings(uSettings: UserSettings) {
        var userSettings: UserSettings? = uSettings
        if (userSettings == null) {
            userSettings = UserSettings()
        }
        userSettings.username = username
        userSettings.notificationEmail
        userSettings.save()
        userManager.userSettings = userSettings
        AppUtil.postEventOnUi(UserSettingsEvent(userSettings))
    }

    private fun writeMessagesUpdates(messagesDatabase: MessagesDatabase, pendingActionsDatabase: PendingActionsDatabase,
                                     events: List<EventResponse.MessageEventBody>, user: User) {
        var latestTimestamp = userManager.checkTimestamp
        for (event in events) {
            event.message?.let {
                latestTimestamp = max(event.message.Time.toFloat(), latestTimestamp)
            }
            val messageID = event.messageID
            writeMessageUpdate(event, pendingActionsDatabase, messageID, messagesDatabase, user)
        }
        userManager.checkTimestamp = latestTimestamp
    }

    private fun writeMessageUpdate(event: EventResponse.MessageEventBody, pendingActionsDatabase: PendingActionsDatabase,
                                   messageID: String, messagesDatabase: MessagesDatabase, user: User) {
        val type = EventType.fromInt(event.type)
        // TODO: check if this is the correct logic
        if (type != EventType.DELETE && checkPendingForSending(pendingActionsDatabase, messageID)) {
            return
        }
        when (type) {
            EventType.CREATE -> {
                try {
                    val savedMessage = messageDetailsRepository.findMessageById(messageID)
                    savedMessage.ifNullElse({
                        messageDetailsRepository.saveMessageInDB(messageFactory.createMessage(event.message))
                        updateMessageFlags(messagesDatabase, messageID, event, user)
                    }, {
                        updateMessageFlags(messagesDatabase, messageID, event, user)
                    })
                } catch (e: JsonSyntaxException) {
                    Logger.doLogException(TAG_EVENT_HANDLER, "unable to create Message object", e)
                }
            }

            EventType.DELETE -> {
                val message = messageDetailsRepository.findMessageById(messageID)
                if (message != null) {
                    messagesDatabase.deleteMessage(message)
                }
            }

            EventType.UPDATE -> {
                // update Message body
                val message = messageDetailsRepository.findMessageById(messageID)
                stagedMessages[messageID]?.let {
                    val dbTime = message?.time ?: 0
                    val serverTime = it.time

                    if (message != null) {
                        message.Attachments = it.Attachments
                    }

                    if (serverTime > dbTime && message != null && it.messageBody != null) {
                        message.messageBody = it.messageBody
                        messageDetailsRepository.saveMessageInDB(message)
                    }

                }

                updateMessageFlags(messagesDatabase, messageID, event, user)
            }

            EventType.UPDATE_FLAGS -> {
                updateMessageFlags(messagesDatabase, messageID, event, user)
            }
        }
        return
    }

    private fun updateMessageFlags(messagesDatabase: MessagesDatabase, messageID: String, item: EventResponse.MessageEventBody, user: User) {
        val message = messageDetailsRepository.findMessageById(messageID)
        val newMessage = item.message

        if (message != null) {

            if (newMessage.Subject != null) {
                message.subject = newMessage.Subject
            }
            if (newMessage.Unread >= 0) {
                message.Unread = newMessage.Unread > 0
            }
            val sender = newMessage.Sender
            if (sender != null) {
                message.sender = MessageSender(sender.Name, sender.Address)
            }
            val toList = newMessage.ToList
            if (toList != null) {
                message.toList = toList
            }
            if (newMessage.Time > 0) {
                message.time = newMessage.Time
            }
            if (newMessage.Size > 0) {
                message.totalSize = newMessage.Size
            }
            if (newMessage.NumAttachments > 0) {
                message.numAttachments = newMessage.NumAttachments
            }
            var expired = false
            if (newMessage.ExpirationTime != -1L) {
                message.expirationTime = newMessage.ExpirationTime
                if (message.expirationTime == 1L) {
                    expired = true
                }
            }
            if (newMessage.Flags > 0) {
                message.isReplied = (newMessage.Flags and MessageFlag.REPLIED.value) == MessageFlag.REPLIED.value
                message.isRepliedAll = (newMessage.Flags and MessageFlag.REPLIED_ALL.value) == MessageFlag.REPLIED_ALL.value
                message.isForwarded = (newMessage.Flags and MessageFlag.FORWARDED.value) == MessageFlag.FORWARDED.value

                message.Type = MessageUtils.calculateType(newMessage.Flags)
                message.setIsEncrypted(MessageUtils.calculateEncryption(newMessage.Flags))
            }
            if (newMessage.AddressID != null) {
                message.addressID = newMessage.AddressID
            }
            val ccList = newMessage.CCList
            if (ccList != null) {
                message.ccList = ccList
            }
            val bccList = newMessage.BCCList
            if (bccList != null) {
                message.bccList = bccList
            }
            if (newMessage.LabelIDs != null) {
                message.setLabelIDs(newMessage.LabelIDs)
            }
            var locationPotentiallyChanged = false
            val eventLabelsAdded = newMessage.LabelIDsAdded
            if (eventLabelsAdded != null) {
                message.addLabels(eventLabelsAdded)
                locationPotentiallyChanged = true
            }
            val eventLabelsRemoved = newMessage.LabelIDsRemoved
            if (eventLabelsRemoved != null) {
                message.removeLabels(eventLabelsRemoved)
                locationPotentiallyChanged = true
            }
            if (locationPotentiallyChanged) {
                message.calculateLocation()
                message.setFolderLocation(messagesDatabase)
            }
            if (expired) {
                messageDetailsRepository.deleteMessage(message)
            } else {
                messageDetailsRepository.saveMessageInDB(message)
                if (message != null && message.Unread){
                    showNotification(user, message)
                }
            }
        } else {
            stagedMessages[messageID]?.let {
                messageDetailsRepository.saveMessageInDB(it)
                showNotification(user, it)
            }
        }
    }

    private fun checkPendingForSending(pendingActionsDao: PendingActionsDao, messageId: String): Boolean {
        var pendingForSending = pendingActionsDao.findPendingSendByMessageId(messageId)
        if (pendingForSending != null) {
            return true
        }
        pendingForSending = pendingActionsDao.findPendingSendByOfflineMessageId(messageId)
        return pendingForSending != null
    }

    private fun writeAddressUpdates(
            events: List<EventResponse.AddressEventBody>,
            currentAddresses: MutableList<Address>?,
            user: User
    ) {
        val addresses = currentAddresses?.toMutableList() ?: mutableListOf()
        val eventAddresses = mutableListOf<Address>()

        for (event in events) {
            try {
                val matcher = { address: Address -> address.id == event.id }

                when (val type = EventType.fromInt(event.type)) {
                    EventType.CREATE, EventType.UPDATE -> {
                        addresses.replaceFirst(event.address, matcher)
                        eventAddresses.add(event.address)
                    }
                    EventType.DELETE -> addresses.removeFirst(matcher)
                    EventType.UPDATE_FLAGS -> { /* Do nothing */
                    }
                    else -> throw NotImplementedError("'$type' not implemented")
                }
            } catch (e: Exception) {
                Logger.doLogException(e)
            }
        }

        AddressKeyActivationWorker.activateAddressKeysIfNeeded(ProtonMailApplication.getApplication(), eventAddresses, username)
        user.setAddresses(addresses)
    }

    private fun writeContactsUpdates(contactsDatabase: ContactsDatabase, events: List<EventResponse.ContactEventBody>) {
        for (event in events) {
            when (EventType.fromInt(event.type)) {
                EventType.CREATE -> {
                    val contact = event.contact

                    val contactId = contact.contactId
                    val contactName = contact.name
                    val contactData = ContactData(contactId, contactName!!)
                    contactsDatabase.saveContactData(contactData)
                    contactsDatabase.insertFullContactDetails(contact)
                }

                EventType.UPDATE -> {
                    val fullContact = event.contact
                    val contactId = fullContact.contactId
                    val contactData = contactsDatabase.findContactDataById(contactId)
                    if (contactData != null) {
                        contactData.name = event.contact.name!!
                        contactsDatabase.saveContactData(contactData)
                    }

                    val localFullContact = contactsDatabase.findFullContactDetailsById(contactId)
                    if (localFullContact != null) {
                        contactsDatabase.deleteFullContactsDetails(localFullContact)
                    }
                    contactsDatabase.insertFullContactDetails(fullContact)
                }

                EventType.DELETE -> {
                    val contactId = event.contactID
                    val contactData = contactsDatabase.findContactDataById(contactId)
                    if (contactData != null) {
                        contactsDatabase.deleteContactData(contactData)
                    }
                }

                EventType.UPDATE_FLAGS -> {}
            }
        }
    }

    private fun writeContactEmailsUpdates(contactsDatabase: ContactsDatabase, events: List<EventResponse.ContactEmailEventBody>) {
        for (event in events) {
            when (EventType.fromInt(event.type)) {
                EventType.CREATE -> {
                    val contactEmail = event.contactEmail
                    val oldContactEmail = contactsDatabase.findContactEmailById(contactEmail.contactEmailId!!) // get current contact email saved in local DB
                    if (oldContactEmail != null) {
                        val contactEmailId = oldContactEmail.contactEmailId!!
                        val joins = contactsDatabase.fetchJoinsByEmail(contactEmailId) as ArrayList
                        contactsDatabase.saveContactEmail(contactEmail)
                        contactsDatabase.saveContactEmailContactLabel(joins)
                    } else {
                        contactsDatabase.saveContactEmail(contactEmail)
                    }
                }

                EventType.UPDATE -> {
                    val contactId = event.contactEmail.contactEmailId
                    val oldContactEmail = contactsDatabase.findContactEmailById(contactId!!) // get current contact email saved in local DB
                    if (oldContactEmail != null) {
                        val updatedContactEmail = event.contactEmail
                        val labelIds = updatedContactEmail.labelIds ?: ArrayList()
                        val contactEmailId = updatedContactEmail.contactEmailId
                        contactEmailId?.let {
                            val joins = contactsDatabase.fetchJoinsByEmail(contactEmailId) as ArrayList
                            contactsDatabase.saveContactEmail(updatedContactEmail)
                            for (labelId in labelIds) {
                                joins.add(ContactEmailContactLabelJoin(contactEmailId, labelId))
                            }
                            contactsDatabase.saveContactEmailContactLabel(joins)
                        }
                    } else {
                        contactsDatabase.saveContactEmail(event.contactEmail)
                    }
                }

                EventType.DELETE -> {
                    val contactId = event.contactID
                    val contactEmail = contactsDatabase.findContactEmailById(contactId)
                    if (contactEmail != null) {
                        contactsDatabase.deleteContactEmail(contactEmail)
                    }
                }

                EventType.UPDATE_FLAGS -> {}
            }
        }
    }

    private fun writeLabelsUpdates(messagesDatabase: MessagesDatabase, contactsDatabase: ContactsDatabase, events: List<EventResponse.LabelsEventBody>) {
        for (event in events) {
            val item = event.label
            when (EventType.fromInt(event.type)) {
                EventType.CREATE -> {
                    val labelType = item.type!!
                    val id = item.ID
                    val name = item.name
                    val color = item.color
                    val display = item.display!!
                    val order = item.order!!
                    val exclusive = item.exclusive!!
                    if (labelType == Constants.LABEL_TYPE_MESSAGE) {
                        val label = Label(id!!, name!!, color!!, display, order, exclusive == 1)
                        messagesDatabase.saveLabel(label)
                    } else if (labelType == Constants.LABEL_TYPE_CONTACT_GROUPS) {
                        val label = ContactLabel(id!!, name!!, color!!, display, order, exclusive == 1)
                        contactsDatabase.saveContactGroupLabel(label)
                    }
                }

                EventType.UPDATE -> {
                    val labelType = item.type!!
                    val labelId = item.ID
                    if (labelType == Constants.LABEL_TYPE_MESSAGE) {
                        val label = messagesDatabase.findLabelById(labelId!!)
                        writeMessageLabel(label, item, messagesDatabase)
                    } else if (labelType == Constants.LABEL_TYPE_CONTACT_GROUPS) {
                        val contactLabel = contactsDatabase.findContactGroupById(labelId!!)
                        writeContactGroup(contactLabel, item, contactsDatabase)
                    }
                }

                EventType.DELETE -> {
                    val labelId = event.id
                    messagesDatabase.deleteLabelById(labelId)
                    contactsDatabase.deleteByContactGroupLabelId(labelId)
                }

                EventType.UPDATE_FLAGS -> {}
            }
        }
    }

    private fun writeUnreadUpdates(messageCounts: List<MessageCount>) {
        val response = UnreadTotalMessagesResponse(messageCounts)
        AppUtil.postEventOnUi(MessageCountsEvent(Status.SUCCESS, response))
    }

    private fun writeMessageLabel(currentLabel: Label?, updatedLabel: ServerLabel, messagesDatabase: MessagesDatabase) {
        if (currentLabel != null) {
            val labelFactory = LabelFactory()
            val labelToSave = labelFactory.createDBObjectFromServerObject(updatedLabel)
            messagesDatabase.saveLabel(labelToSave)
        }
    }

    private fun writeContactGroup(currentGroup: ContactLabel?, updatedGroup: ServerLabel, contactsDatabase: ContactsDatabase) {
        if (currentGroup != null) {
            val contactLabelFactory = ContactLabelFactory()
            val labelToSave = contactLabelFactory.createDBObjectFromServerObject(updatedGroup)
            val joins = contactsDatabase.fetchJoins(labelToSave.ID)
            contactsDatabase.saveContactGroupLabel(labelToSave)
            contactsDatabase.saveContactEmailContactLabel(joins)
        }
    }
}
