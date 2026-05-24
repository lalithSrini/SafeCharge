package com.example.safecharge;

import java.io.File;
import java.util.List;

/**
 * Legacy stub — kept so any remaining references compile.
 * All upload logic has moved to CloudinaryUploader.
 * Safe to delete this file once you confirm everything works.
 */
public class FirebaseUploader {

    public interface UploadCallback {
        void onComplete(List<String> downloadUrls);
    }

    /** Delegates everything to CloudinaryUploader. */
    public static void uploadPhotos(List<File> photos,
                                    CloudinaryUploader.UploadCallback callback) {
        CloudinaryUploader.uploadPhotos(photos, callback);
    }
}