package com.github.s0nerik.fast_contacts

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Email
import androidx.annotation.NonNull
import androidx.core.content.ContentResolverCompat
import androidx.lifecycle.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.IOException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


/** FastContactsPlugin */
class FastContactsPlugin : FlutterPlugin, MethodCallHandler, LifecycleOwner, ViewModelStoreOwner {
    private lateinit var channel: MethodChannel
    private lateinit var contentResolver: ContentResolver
    private lateinit var handler: Handler

    private val contactsExecutor = ThreadPoolExecutor(
            4, Integer.MAX_VALUE,
            20L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>()
    )

    private val imageExecutor = ThreadPoolExecutor(
            4, Integer.MAX_VALUE,
            20L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>()
    )

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.github.s0nerik.fast_contacts")
        handler = Handler(flutterPluginBinding.applicationContext.mainLooper)
        contentResolver = flutterPluginBinding.applicationContext.contentResolver
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getContacts" -> {
                getContacts(result, TargetInfo.PHONES, TargetInfo.EMAILS)
            }
            "getContactImage" -> {
                val args = call.arguments as Map<String, String>
                val contactId = args.getValue("id").toLong()
                if (args["size"] == "thumbnail") {
                    ContactThumbnailLoaderAsyncTask(
                            handler = handler,
                            contentResolver = contentResolver,
                            contactId = contactId,
                            onResult = { result.success(it) },
                            onError = {
                                result.error("", it.localizedMessage, it.toString())
                            }
                    ).executeOnExecutor(imageExecutor)
                } else {
                    ContactImageLoaderAsyncTask(
                            handler = handler,
                            contentResolver = contentResolver,
                            contactId = contactId,
                            onResult = { result.success(it) },
                            onError = {
                                result.error("", it.localizedMessage, it.toString())
                            }
                    ).executeOnExecutor(imageExecutor)
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun getLifecycle(): Lifecycle {
        val registry = LifecycleRegistry(this)
        registry.currentState = Lifecycle.State.RESUMED
        return registry
    }

    override fun getViewModelStore(): ViewModelStore {
        return ViewModelStore()
    }

    private fun getContacts(result: Result, vararg targetInfo: TargetInfo) {
        val signalledError = AtomicBoolean(false)
        val signalledValue = AtomicBoolean(false)
        val results = mutableMapOf<TargetInfo, Map<Long, Contact>>()

        fun handleResultsReady() {
            val mergedContacts = mergeContactsInfo(results.values)
            handler.post {
                result.success(mergedContacts.map(Contact::asMap))
            }
        }

        fun withResultHandler(target: TargetInfo, action: () -> Map<Long, Contact>) {
            try {
                results[target] = action()
                if (results.size == targetInfo.size && !signalledError.get() && !signalledValue.getAndSet(true)) {
                    handleResultsReady()
                }
            } catch (e: Exception) {
                if (!signalledError.getAndSet(true)) {
                    handler.post {
                        result.error("", e.localizedMessage, e.toString())
                    }
                }
            }
        }

        for (target in targetInfo) {
            when (target) {
                TargetInfo.BASIC -> TODO()
                TargetInfo.PHONES -> contactsExecutor.execute {
                    withResultHandler(TargetInfo.PHONES, ::readPhonesInfo)
                }
                TargetInfo.EMAILS -> contactsExecutor.execute {
                    withResultHandler(TargetInfo.EMAILS, ::readEmailsInfo)
                }
            }
        }
    }

    private fun mergeContactsInfo(allContactsInfo: Collection<Map<Long, Contact>>): Collection<Contact> {
        val mergedContacts = mutableMapOf<Long, Contact>()

        val contactsToMerge = mutableListOf<Contact>()
        for (contactsMap in allContactsInfo) {
            for (contactId in contactsMap.keys) {
                if (mergedContacts.containsKey(contactId)) {
                    continue
                }
                contactsToMerge.clear()
                for (contacts in allContactsInfo) {
                    val c = contacts[contactId]
                    if (c != null) {
                        contactsToMerge.add(c)
                    }
                }
                mergedContacts[contactId] = Contact.mergeInPlace(contactsToMerge)
            }
        }

        return mergedContacts.values
    }

    private fun readPhonesInfo(): Map<Long, Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        readTargetInfo(TargetInfo.PHONES) { projection, cursor ->
            val contactId = cursor.getLong(projection.indexOf(Phone.CONTACT_ID))
            val displayName = cursor.getString(projection.indexOf(Phone.DISPLAY_NAME)) ?: ""
            val phone = cursor.getString(projection.indexOf(Phone.NUMBER)) ?: ""

            if (contacts.containsKey(contactId)) {
                (contacts[contactId]!!.phones as MutableList<String>).add(phone)
            } else {
                contacts[contactId] = Contact(
                        id = contactId.toString(),
                        displayName = displayName,
                        phones = mutableListOf(phone),
                        emails = listOf()
                )
            }
        }
        return contacts
    }

    private fun readEmailsInfo(): Map<Long, Contact> {
        val contacts = mutableMapOf<Long, Contact>()
        readTargetInfo(TargetInfo.EMAILS) { projection, cursor ->
            val contactId = cursor.getLong(projection.indexOf(Email.CONTACT_ID))
            val displayName = cursor.getString(projection.indexOf(Email.DISPLAY_NAME)) ?: ""
            val email = cursor.getString(projection.indexOf(Email.ADDRESS)) ?: ""

            if (contacts.containsKey(contactId)) {
                (contacts[contactId]!!.emails as MutableList<String>).add(email)
            } else {
                contacts[contactId] = Contact(
                        id = contactId.toString(),
                        displayName = displayName,
                        phones = listOf(),
                        emails = mutableListOf(email)
                )
            }
        }
        return contacts
    }

    private fun readTargetInfo(targetInfo: TargetInfo, onData: (projection: Array<String>, cursor: Cursor) -> Unit) {
        val cursor = ContentResolverCompat.query(contentResolver, CONTENT_URI[targetInfo],
                PROJECTION[targetInfo], null, null, SORT_ORDER[targetInfo], null)
        cursor?.use {
            while (!cursor.isClosed && cursor.moveToNext()) {
                onData(PROJECTION[targetInfo]!!, cursor)
            }
        }
    }

    companion object {
        private val CONTENT_URI = mapOf(
                TargetInfo.PHONES to Phone.CONTENT_URI,
                TargetInfo.EMAILS to Email.CONTENT_URI
        )
        private val PROJECTION = mapOf(
                TargetInfo.PHONES to arrayOf(
                        Phone.CONTACT_ID,
                        Phone.DISPLAY_NAME,
                        Phone.NUMBER
                ),
                TargetInfo.EMAILS to arrayOf(
                        Email.CONTACT_ID,
                        Email.DISPLAY_NAME,
                        Email.ADDRESS
                )
        )
        private val SORT_ORDER = mapOf(
                TargetInfo.PHONES to "${Phone.DISPLAY_NAME} ASC",
                TargetInfo.EMAILS to "${Email.DISPLAY_NAME} ASC"
        )
    }
}

private enum class TargetInfo {
    BASIC, PHONES, EMAILS;

    companion object {
        fun fromString(str: String): TargetInfo {
            return when (str) {
                "basic" -> BASIC
                "emails" -> EMAILS
                "phones" -> PHONES
                else -> BASIC
            }
        }
    }
}

private class ContactThumbnailLoaderAsyncTask(
        private val handler: Handler,
        private val contentResolver: ContentResolver,
        private val contactId: Long,
        private val onResult: (ByteArray?) -> Unit,
        private val onError: (Exception) -> Unit
) : AsyncTask<Void, Void, Unit>() {
    override fun doInBackground(vararg params: Void?) {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        try {
            val cursor = contentResolver.query(
                    Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY),
                    arrayOf(ContactsContract.Contacts.Photo.PHOTO),
                    null,
                    null,
                    null
            )
            if (cursor != null) {
                cursor.use {
                    if (cursor.moveToNext()) {
                        val result = cursor.getBlob(0)
                        handler.post {
                            onResult(result)
                        }
                    } else {
                        handler.post {
                            onResult(null)
                        }
                    }
                }
            } else {
                handler.post {
                    onResult(null)
                }
            }
        } catch (e: IOException) {
            handler.post {
                onError(e)
            }
        }
    }
}

private class ContactImageLoaderAsyncTask(
        private val handler: Handler,
        private val contentResolver: ContentResolver,
        private val contactId: Long,
        private val onResult: (ByteArray?) -> Unit,
        private val onError: (Exception) -> Unit
) : AsyncTask<Void, Void, Unit>() {
    override fun doInBackground(vararg params: Void?) {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val displayPhotoUri = Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.DISPLAY_PHOTO)
        try {
            val fd = contentResolver.openAssetFileDescriptor(displayPhotoUri, "r")
            if (fd != null) {
                fd.use {
                    fd.createInputStream().use {
                        val result = it.readBytes()
                        handler.post {
                            onResult(result)
                        }
                    }
                }
            } else {
                handler.post {
                    onResult(null)
                }
            }
        } catch (e: IOException) {
            handler.post {
                onError(e)
            }
        }
    }
}