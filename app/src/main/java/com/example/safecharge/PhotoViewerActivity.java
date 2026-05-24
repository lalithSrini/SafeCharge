package com.example.safecharge;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Displays captured thief photos using a RecyclerView.
 *
 * Optimized with Glide for asynchronous image loading and memory caching
 * to ensure smooth scrolling and eliminate UI lag.
 */
public class PhotoViewerActivity extends AppCompatActivity {

    private static final String FOLDER       = "SafeCharge";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        findViewById(R.id.btnDeleteAll).setOnClickListener(v -> confirmDeleteAll());

        List<File> photos = getPhotos();
        TextView tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView recyclerView = findViewById(R.id.recyclerPhotos);

        if (photos.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(new PhotoAdapter(photos));
        }
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

        private final List<File> photos;

        PhotoAdapter(List<File> photos) {
            this.photos = photos;
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View card = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo_card, parent, false);
            return new PhotoViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            holder.bind(photos.get(position));
        }

        @Override
        public int getItemCount() { return photos.size(); }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            private final TextView  tvCamera;
            private final TextView  tvTimestamp;
            private final ImageView imageView;

            PhotoViewHolder(View itemView) {
                super(itemView);
                tvCamera    = itemView.findViewById(R.id.tvCamera);
                tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
                imageView   = itemView.findViewById(R.id.photoImage);
            }

            void bind(File photo) {
                String name = photo.getName();
                tvCamera.setText(name.contains("front") ? "📷 Front Camera" : "📷 Back Camera");
                tvTimestamp.setText(formatTimestamp(name));

                Glide.with(itemView.getContext())
                        .load(photo)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(imageView);
            }
        }
    }

    private String formatTimestamp(String filename) {
        try {
            String[] parts = filename.replace(".jpg", "").split("_");
            String date = parts[2];
            String time = parts[3];
            return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6)
                    + "  " + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4);
        } catch (Exception e) {
            return filename;
        }
    }

    private List<File> getPhotos() {
        File dir = new File(getFilesDir(), FOLDER);
        if (!dir.exists()) return new ArrayList<>();
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jpg"));
        if (files == null) return new ArrayList<>();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete All Photos?")
                .setMessage("This will permanently remove all captured thief photos.")
                .setPositiveButton("Delete", (d, w) -> deleteAllPhotos())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAllPhotos() {
        File dir = new File(getFilesDir(), FOLDER);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        }

        ContentResolver cr = getContentResolver();
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        String[] args = { "%" + FOLDER + "%" };
        try {
            Cursor cursor = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    selection, args, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id  = cursor.getLong(0);
                    Uri  uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    cr.delete(uri, null, null);
                }
                cursor.close();
            }
        } catch (Exception e) {
            // Gallery cleanup best-effort — private copies already removed
        }

        Toast.makeText(this, "All photos deleted 🗑", Toast.LENGTH_SHORT).show();
        finish();
    }
}