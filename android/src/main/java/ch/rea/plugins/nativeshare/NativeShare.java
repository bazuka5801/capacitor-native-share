package ch.rea.plugins.nativeshare;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.getcapacitor.JSObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;

public class NativeShare {

    // Check if a file exists and is readable
    private static void validateFile(String path) throws IOException {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            throw new IOException("File not accessible: " + path);
        }
    }

    // Access content URI and ensure it is readable
    private static void validateContentUri(Context context, String uriString) throws IOException {
        Uri uri = Uri.parse(uriString);
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open input stream for URI: " + uriString);
            }
        }
    }

    // Attempt to access a file or content URI
    private static boolean tryAccess(String path, boolean isContent, Context context) {
        try {
            if (isContent) {
                validateContentUri(context, path);
            } else {
                validateFile(path);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Extract file name from a URI
    private static String getFileNameFromUri(Uri uri, Context context) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e("[CACHE]", "Error retrieving file name: " + e.getMessage());
            }
        } else if ("file".equals(uri.getScheme())) {
            return new File(Objects.requireNonNull(uri.getPath())).getName();
        }
        return "cached_file"; // Default name if extraction fails
    }

    // Cache a file from a content URI
    private static String cacheFileFromUri(Context context, Uri contentUri, String fileName) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(contentUri)) {
            if (inputStream != null) {
                File cacheDir = context.getCacheDir();
                File cachedFile = new File(cacheDir, fileName);

                try (FileOutputStream outputStream = new FileOutputStream(cachedFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                return cachedFile.getAbsolutePath();
            }
        } catch (IOException e) {
            Log.e("[CACHE]", "Error caching file: " + e.getMessage());
        }
        return null;
    }

    // Handle single intent with a file or content URI
    public static JSObject[] handleSendIntent(Context context, Intent intent) {
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

        String uriStr = uri != null ? uri.toString() : "";
        String realPath = FileHelperCap.getRealPathFromURI_API11_And_Above(context, uri);

        if (!tryAccess(realPath, false, context)) {
            realPath = uriStr;
            if (tryAccess(realPath, true, context)) {
                Uri contentUri = Uri.parse(uriStr);
                String fileName = getFileNameFromUri(contentUri, context);
                realPath = cacheFileFromUri(context, contentUri, fileName);
            } else {
                Log.e("[handleSendIntent]", "cant access neither realpath nor content path");
            }
        } else {
            Log.d("[handleSendIntent]", "can access realpath");
        }

        String realUri = Uri.fromFile(new File(realPath)).toString();
        String mimeType = FileHelperCap.getMimeType(uriStr, context.getContentResolver());

        JSObject item = new JSObject();
        item.put("mimeType", mimeType);
        item.put("text", text != null ? text : "");
        item.put("uri", realUri);

        return new JSObject[]{item};
    }

    // Handle multiple intents with files or content URIs
    public static JSObject[] handleSendMultipleIntent(Context context, Intent intent) {
        ArrayList<Uri> uriList = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (uriList == null) return new JSObject[0];

        JSObject[] items = new JSObject[uriList.size()];
        for (int i = 0; i < uriList.size(); i++) {
            Uri uri = uriList.get(i);
            String uriStr = uri != null ? uri.toString() : "";
            String realPath = FileHelperCap.getRealPathFromURI_API11_And_Above(context, uri);

            if (!tryAccess(realPath, false, context)) {
                realPath = uriStr;
                if (tryAccess(realPath, true, context)) {
                    Uri contentUri = Uri.parse(uriStr);
                    String fileName = getFileNameFromUri(contentUri, context);
                    realPath = cacheFileFromUri(context, contentUri, fileName);
                } else {
                    Log.e("[handleSendIntent]", "cant access neither realpath nor content path for URI: " + uriStr);
                    // You might want to handle this error differently, e.g., skip the item
//                    continue;
                }
            } else {
                Log.d("[handleSendIntent]", "can access realpath for URI: " + uriStr);
            }

            String realUri = Uri.fromFile(new File(realPath)).toString();
            String mimeType = FileHelperCap.getMimeType(uriStr, context.getContentResolver());

            JSObject item = new JSObject();
            item.put("mimeType", mimeType);
            item.put("text", "");
            item.put("uri", realUri);
            items[i] = item;
        }

        return items;
    }
} 