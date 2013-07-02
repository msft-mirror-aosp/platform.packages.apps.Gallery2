/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.Video;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.camera.Util;
import com.android.camera.ui.FilmStripView;
import com.android.gallery3d.R;

import java.io.File;
import java.util.Comparator;
import java.util.Date;

/* An abstract interface that represents the local media data. Also implements
 * Comparable interface so we can sort in DataAdapter.
 */
public interface LocalData extends FilmStripView.ImageData {
    static final String TAG = "CAM_LocalData";

    public static final int ACTION_NONE = 0;
    public static final int ACTION_PLAY = 1;
    public static final int ACTION_DELETE = (1 << 1);

    View getView(Context c, int width, int height, Drawable placeHolder);
    long getDateTaken();
    long getDateModified();
    String getTitle();
    boolean isDataActionSupported(int action);
    boolean delete(Context c);

    static class NewestFirstComparator implements Comparator<LocalData> {

        // Compare taken/modified date of LocalData in descent order to make
        // newer data in the front.
        // The negavive numbers here are always considered "bigger" than
        // postive ones. Thus, if any one of the numbers is negative, the logic
        // is reversed.
        private static int compareDate(long v1, long v2) {
            if (v1 >= 0 && v2 >= 0) {
                return ((v1 < v2) ? 1 : ((v1 > v2) ? -1 : 0));
            }
            return ((v2 < v1) ? 1 : ((v2 > v1) ? -1 : 0));
        }

        @Override
        public int compare(LocalData d1, LocalData d2) {
            int cmp = compareDate(d1.getDateTaken(), d2.getDateTaken());
            if (cmp == 0) {
                cmp = compareDate(d1.getDateModified(), d2.getDateModified());
            }
            if (cmp == 0) {
                cmp = d1.getTitle().compareTo(d2.getTitle());
            }
            return cmp;
        }
    }

    // Implementations below.

    /*
     * A base class for all the local media files. The bitmap is loaded in background
     * thread. Subclasses should implement their own background loading thread by
     * subclassing BitmapLoadTask and overriding doInBackground() to return a bitmap.
     */
    abstract static class LocalMediaData implements LocalData {
        protected long id;
        protected String title;
        protected String mimeType;
        protected long dateTaken;
        protected long dateModified;
        protected String path;
        // width and height should be adjusted according to orientation.
        protected int width;
        protected int height;

        // true if this data has a corresponding visible view.
        protected Boolean mUsing = false;

        @Override
        public long getDateTaken() {
            return dateTaken;
        }

        @Override
        public long getDateModified() {
            return dateModified;
        }

        @Override
        public String getTitle() {
            return new String(title);
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return false;
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return false;
        }

        @Override
        public boolean delete(Context ctx) {
            File f = new File(path);
            return f.delete();
        }

        protected ImageView fillImageView(Context ctx, ImageView v,
                int decodeWidth, int decodeHeight, Drawable placeHolder) {
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setImageDrawable(placeHolder);

            BitmapLoadTask task = getBitmapLoadTask(v, decodeWidth, decodeHeight);
            task.execute();
            return v;
        }

        @Override
        public View getView(Context ctx,
                int decodeWidth, int decodeHeight, Drawable placeHolder) {
            return fillImageView(ctx, new ImageView(ctx),
                    decodeWidth, decodeHeight, placeHolder);
        }

        @Override
        public void prepare() {
            synchronized (mUsing) {
                mUsing = true;
            }
        }

        @Override
        public void recycle() {
            synchronized (mUsing) {
                mUsing = false;
            }
        }

        protected boolean isUsing() {
            synchronized (mUsing) {
                return mUsing;
            }
        }

        @Override
        public abstract int getType();

        protected abstract BitmapLoadTask getBitmapLoadTask(
                ImageView v, int decodeWidth, int decodeHeight);

        /*
         * An AsyncTask class that loads the bitmap in the background thread.
         * Sub-classes should implement their own "protected Bitmap doInBackground(Void... )"
         */
        protected abstract class BitmapLoadTask extends AsyncTask<Void, Void, Bitmap> {
            protected ImageView mView;

            protected BitmapLoadTask(ImageView v) {
                mView = v;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (!isUsing()) return;
                if (bitmap == null) {
                    Log.e(TAG, "Failed decoding bitmap for file:" + path);
                    return;
                }
                BitmapDrawable d = new BitmapDrawable(bitmap);
                mView.setScaleType(ImageView.ScaleType.FIT_XY);
                mView.setImageDrawable(d);
            }
        }
    }

    static class Photo extends LocalMediaData {
        public static final int COL_ID = 0;
        public static final int COL_TITLE = 1;
        public static final int COL_MIME_TYPE = 2;
        public static final int COL_DATE_TAKEN = 3;
        public static final int COL_DATE_MODIFIED = 4;
        public static final int COL_DATA = 5;
        public static final int COL_ORIENTATION = 6;
        public static final int COL_WIDTH = 7;
        public static final int COL_HEIGHT = 8;

        static final Uri CONTENT_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        static final String QUERY_ORDER = ImageColumns.DATE_TAKEN + " DESC, "
                + ImageColumns._ID + " DESC";
        static final String[] QUERY_PROJECTION = {
            ImageColumns._ID,           // 0, int
            ImageColumns.TITLE,         // 1, string
            ImageColumns.MIME_TYPE,     // 2, string
            ImageColumns.DATE_TAKEN,    // 3, int
            ImageColumns.DATE_MODIFIED, // 4, int
            ImageColumns.DATA,          // 5, string
            ImageColumns.ORIENTATION,   // 6, int, 0, 90, 180, 270
            ImageColumns.WIDTH,         // 7, int
            ImageColumns.HEIGHT,        // 8, int
        };

        private static final int mSupportedUIActions =
                FilmStripView.ImageData.ACTION_DEMOTE
                | FilmStripView.ImageData.ACTION_PROMOTE;
        private static final int mSupportedDataActions =
                LocalData.ACTION_DELETE;

        // 32K buffer.
        private static final byte[] DECODE_TEMP_STORAGE = new byte[32 * 1024];

        // from MediaStore, can only be 0, 90, 180, 270;
        public int orientation;

        static Photo buildFromCursor(Cursor c) {
            Photo d = new Photo();
            d.id = c.getLong(COL_ID);
            d.title = c.getString(COL_TITLE);
            d.mimeType = c.getString(COL_MIME_TYPE);
            d.dateTaken = c.getLong(COL_DATE_TAKEN);
            d.dateModified = c.getLong(COL_DATE_MODIFIED);
            d.path = c.getString(COL_DATA);
            d.orientation = c.getInt(COL_ORIENTATION);
            d.width = c.getInt(COL_WIDTH);
            d.height = c.getInt(COL_HEIGHT);
            if (d.width <= 0 || d.height <= 0) {
                Log.w(TAG, "Warning! zero dimension for "
                        + d.path + ":" + d.width + "x" + d.height);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(d.path, opts);
                if (opts.outWidth != -1 && opts.outHeight != -1)  {
                    d.width = opts.outWidth;
                    d.height = opts.outHeight;
                } else {
                    Log.w(TAG, "Warning! dimension decode failed for " + d.path);
                    Bitmap b = BitmapFactory.decodeFile(d.path);
                    if (b == null) {
                        return null;
                    }
                    d.width = b.getWidth();
                    d.height = b.getHeight();
                }
            }
            if (d.orientation == 90 || d.orientation == 270) {
                int b = d.width;
                d.width = d.height;
                d.height = b;
            }
            return d;
        }

        @Override
        public String toString() {
            return "Photo:" + ",data=" + path + ",mimeType=" + mimeType
                    + "," + width + "x" + height + ",orientation=" + orientation
                    + ",date=" + new Date(dateTaken);
        }

        @Override
        public int getType() {
            return TYPE_PHOTO;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return ((action & mSupportedUIActions) == action);
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return ((action & mSupportedDataActions) == action);
        }

        @Override
        public boolean delete(Context c) {
            ContentResolver cr = c.getContentResolver();
            cr.delete(CONTENT_URI, ImageColumns._ID + "=" + id, null);
            return super.delete(c);
        }

        @Override
        protected BitmapLoadTask getBitmapLoadTask(
                ImageView v, int decodeWidth, int decodeHeight) {
            return new PhotoBitmapLoadTask(v, decodeWidth, decodeHeight);
        }

        private final class PhotoBitmapLoadTask extends BitmapLoadTask {
            private int mDecodeWidth;
            private int mDecodeHeight;

            public PhotoBitmapLoadTask(ImageView v, int decodeWidth, int decodeHeight) {
                super(v);
                mDecodeWidth = decodeWidth;
                mDecodeHeight = decodeHeight;
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                BitmapFactory.Options opts = null;
                Bitmap b;
                int sample = 1;
                while (mDecodeWidth * sample < width
                        || mDecodeHeight * sample < height) {
                    sample *= 2;
                }
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inTempStorage = DECODE_TEMP_STORAGE;
                if (isCancelled() || !isUsing()) {
                    return null;
                }
                b = BitmapFactory.decodeFile(path, opts);
                if (orientation != 0) {
                    if (isCancelled() || !isUsing()) {
                        return null;
                    }
                    Matrix m = new Matrix();
                    m.setRotate((float) orientation);
                    b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
                }
                return b;
            }
        }
    }

    static class Video extends LocalMediaData {
        public static final int COL_ID = 0;
        public static final int COL_TITLE = 1;
        public static final int COL_MIME_TYPE = 2;
        public static final int COL_DATE_TAKEN = 3;
        public static final int COL_DATE_MODIFIED = 4;
        public static final int COL_DATA = 5;
        public static final int COL_WIDTH = 6;
        public static final int COL_HEIGHT = 7;

        static final Uri CONTENT_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        private static final int mSupportedUIActions =
                FilmStripView.ImageData.ACTION_DEMOTE
                | FilmStripView.ImageData.ACTION_PROMOTE;
        private static final int mSupportedDataActions =
                LocalData.ACTION_DELETE
                | LocalData.ACTION_PLAY;

        static final String QUERY_ORDER = VideoColumns.DATE_TAKEN + " DESC, "
                + VideoColumns._ID + " DESC";
        static final String[] QUERY_PROJECTION = {
            VideoColumns._ID,           // 0, int
            VideoColumns.TITLE,         // 1, string
            VideoColumns.MIME_TYPE,     // 2, string
            VideoColumns.DATE_TAKEN,    // 3, int
            VideoColumns.DATE_MODIFIED, // 4, int
            VideoColumns.DATA,          // 5, string
            VideoColumns.WIDTH,         // 6, int
            VideoColumns.HEIGHT,        // 7, int
            VideoColumns.RESOLUTION
        };

        private Uri mPlayUri;

        static Video buildFromCursor(Cursor c) {
            Video d = new Video();
            d.id = c.getLong(COL_ID);
            d.title = c.getString(COL_TITLE);
            d.mimeType = c.getString(COL_MIME_TYPE);
            d.dateTaken = c.getLong(COL_DATE_TAKEN);
            d.dateModified = c.getLong(COL_DATE_MODIFIED);
            d.path = c.getString(COL_DATA);
            d.width = c.getInt(COL_WIDTH);
            d.height = c.getInt(COL_HEIGHT);
            d.mPlayUri = CONTENT_URI.buildUpon()
                    .appendPath(String.valueOf(d.id)).build();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(d.path);
            String rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (d.width == 0 || d.height == 0) {
                d.width = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                d.height = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            }
            retriever.release();
            if (rotation != null
                    && (rotation.equals("90") || rotation.equals("270"))) {
                int b = d.width;
                d.width = d.height;
                d.height = b;
            }
            return d;
        }

        @Override
        public String toString() {
            return "Video:" + ",data=" + path + ",mimeType=" + mimeType
                    + "," + width + "x" + height + ",date=" + new Date(dateTaken);
        }

        @Override
        public int getType() {
            return TYPE_PHOTO;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return ((action & mSupportedUIActions) == action);
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return ((action & mSupportedDataActions) == action);
        }

        @Override
        public boolean delete(Context ctx) {
            ContentResolver cr = ctx.getContentResolver();
            cr.delete(CONTENT_URI, VideoColumns._ID + "=" + id, null);
            return super.delete(ctx);
        }

        @Override
        public View getView(final Context ctx,
                int decodeWidth, int decodeHeight, Drawable placeHolder) {

            // ImageView for the bitmap.
            ImageView iv = new ImageView(ctx);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            fillImageView(ctx, iv, decodeWidth, decodeHeight, placeHolder);

            // ImageView for the play icon.
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.ic_control_play);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            icon.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Util.playVideo(ctx, mPlayUri, title);
                }
            });

            FrameLayout f = new FrameLayout(ctx);
            f.addView(iv);
            f.addView(icon);
            return f;
        }

        @Override
        protected BitmapLoadTask getBitmapLoadTask(
                ImageView v, int decodeWidth, int decodeHeight) {
            return new VideoBitmapLoadTask(v);
        }

        private final class VideoBitmapLoadTask extends BitmapLoadTask {

            public VideoBitmapLoadTask(ImageView v) {
                super(v);
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                if (isCancelled() || !isUsing()) {
                    return null;
                }
                android.media.MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(path);
                byte[] data = retriever.getEmbeddedPicture();
                Bitmap bitmap = null;
                if (isCancelled() || !isUsing()) {
                    retriever.release();
                    return null;
                }
                if (data != null) {
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                }
                if (bitmap == null) {
                    bitmap = (Bitmap) retriever.getFrameAtTime();
                }
                retriever.release();
                return bitmap;
            }
        }
    }

    /*
     * A LocalData that does nothing but only shows a view.
     */
    public static class LocalViewData implements LocalData {
        private int mWidth;
        private int mHeight;
        View mView;
        private long mDateTaken;
        private long mDateModified;

        public LocalViewData(View v,
                int width, int height,
                int dateTaken, int dateModified) {
            mView = v;
            mWidth = width;
            mHeight = height;
            mDateTaken = dateTaken;
            mDateModified = dateModified;
        }

        @Override
        public long getDateTaken() {
            return mDateTaken;
        }

        @Override
        public long getDateModified() {
            return mDateModified;
        }

        @Override
        public String getTitle() {
            return "";
        }

        @Override
        public int getWidth() {
            return mWidth;
        }

        @Override
        public int getHeight() {
            return mHeight;
        }

        @Override
        public int getType() {
            return FilmStripView.ImageData.TYPE_PHOTO;
        }

        @Override
        public boolean isUIActionSupported(int action) {
            return false;
        }

        @Override
        public boolean isDataActionSupported(int action) {
            return false;
        }

        @Override
        public boolean delete(Context c) {
            return false;
        }

        @Override
        public View getView(Context c, int width, int height, Drawable placeHolder) {
            return mView;
        }

        @Override
        public void prepare() {
            // do nothing.
        }

        @Override
        public void recycle() {
            // do nothing.
        }
    }
}
