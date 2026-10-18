package io.github.lswlc33.zeehowebnext;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只暴露缓存目录里那一个更新 APK 的 ContentProvider。
 *
 * targetSdk 28+ 不能把 file:// 递给安装器（FileUriExposedException），而工程没有 AndroidX，
 * 所以不用 FileProvider：自己实现一个最小只读 provider，路径由代码写死，不接收任意路径。
 */
public final class UpdateProvider extends ContentProvider {

    static final String AUTHORITY_SUFFIX = ".update";
    private static final String FILE_NAME = "zeehowebnext.apk";

    static Uri uriFor(Context context) {
        return new Uri.Builder()
            .scheme("content")
            .authority(context.getPackageName() + AUTHORITY_SUFFIX)
            .appendPath(FILE_NAME)
            .build();
    }

    static File apkFile(Context context) {
        return new File(new File(context.getCacheDir(), "update"), FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || !FILE_NAME.equals(uri.getLastPathSegment()) || !"r".equals(mode)) {
            throw new FileNotFoundException("unsupported update uri");
        }
        File file = apkFile(getContext());
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FileNotFoundException("update apk missing");
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (uri == null || !FILE_NAME.equals(uri.getLastPathSegment())) return null;
        File file = apkFile(getContext());
        String[] cols = projection != null ? projection
            : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = FILE_NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = file.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
