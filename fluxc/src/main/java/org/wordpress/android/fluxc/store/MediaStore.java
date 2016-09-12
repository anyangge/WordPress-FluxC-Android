package org.wordpress.android.fluxc.store;

import android.support.annotation.NonNull;

import com.wellsql.generated.MediaModelTable;

import org.greenrobot.eventbus.Subscribe;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.Payload;
import org.wordpress.android.fluxc.action.MediaAction;
import org.wordpress.android.fluxc.annotations.action.Action;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.network.BaseRequest;
import org.wordpress.android.fluxc.network.BaseUploadRequestBody;
import org.wordpress.android.fluxc.network.rest.wpcom.media.MediaRestClient;
import org.wordpress.android.fluxc.network.xmlrpc.media.MediaXMLRPCClient;
import org.wordpress.android.fluxc.persistence.MediaSqlUtils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class MediaStore extends Store {
    //
    // Payloads
    //

    /**
     * Actions: FETCH_ALL_MEDIA, FETCH(ED)_MEDIA, PUSH(ED)_MEDIA, DELETE(D)_MEDIA, UPDATE_MEDIA, and REMOVE_MEDIA
     */
    public static class MediaListPayload extends Payload {
        public MediaAction cause;
        public SiteModel site;
        public List<MediaModel> media;
        public MediaError error;
        public MediaListPayload(MediaAction cause, SiteModel site, List<MediaModel> media) {
            this.cause = cause;
            this.site = site;
            this.media = media;
        }

        @Override
        public boolean isError() {
            return error != null;
        }
    }

    /**
     * Actions: UPLOAD_MEDIA
     */
    public static class UploadMediaPayload extends Payload {
        public SiteModel site;
        public MediaModel media;
        public UploadMediaPayload(SiteModel site, MediaModel media) {
            this.site = site;
            this.media = media;
        }
    }

    /**
     * Actions: UPLOADED_MEDIA
     */
    public static class ProgressPayload extends Payload {
        public MediaModel media;
        public float progress;
        public boolean completed;
        public MediaError error;
        public ProgressPayload(MediaModel media, float progress, boolean completed) {
            this.media = media;
            this.progress = progress;
            this.completed = completed;
        }

        @Override
        public boolean isError() {
            return error != null;
        }
    }

    //
    // Errors
    //

    public enum MediaErrorType {
        // local errors, occur before sending network requests
        FS_READ_PERMISSION_DENIED,
        NULL_MEDIA_ARG,
        MALFORMED_MEDIA_ARG,

        // network errors, occur in response to network requests
        MEDIA_NOT_FOUND,
        UNAUTHORIZED,
        PARSE_ERROR,

        // unknown/unspecified
        GENERIC_ERROR;

        public static MediaErrorType fromBaseNetworkError(BaseRequest.BaseNetworkError baseError) {
            switch (baseError.type) {
                case NOT_FOUND:
                    return MediaErrorType.MEDIA_NOT_FOUND;
                case AUTHORIZATION_REQUIRED:
                    return MediaErrorType.UNAUTHORIZED;
                case PARSE_ERROR:
                    return MediaErrorType.PARSE_ERROR;
                default:
                    return MediaErrorType.GENERIC_ERROR;
            }
        }

        public static MediaErrorType fromHttpStatusCode(int code) {
            switch (code) {
                case 404:
                    return MediaErrorType.MEDIA_NOT_FOUND;
                case 403:
                    return MediaErrorType.UNAUTHORIZED;
                default:
                    return MediaErrorType.GENERIC_ERROR;
            }
        }
    }

    public static class MediaError implements OnChangedError {
        public MediaErrorType type;
        public MediaError(MediaErrorType type) {
            this.type = type;
        }
    }

    //
    // OnChanged events
    //

    public class OnMediaChanged extends OnChanged<MediaError> {
        public MediaAction cause;
        public List<MediaModel> media;
        public OnMediaChanged(MediaAction cause, List<MediaModel> media) {
            this.cause = cause;
            this.media = media;
        }
    }

    public class OnMediaUploaded extends OnChanged<MediaError> {
        public MediaModel media;
        public float progress;
        public boolean completed;
        public OnMediaUploaded(MediaModel media, float progress, boolean completed) {
            this.media = media;
            this.progress = progress;
            this.completed = completed;
        }
    }

    private MediaRestClient mMediaRestClient;
    private MediaXMLRPCClient mMediaXmlrpcClient;

    @Inject
    public MediaStore(Dispatcher dispatcher, MediaRestClient restClient, MediaXMLRPCClient xmlrpcClient) {
        super(dispatcher);
        mMediaRestClient = restClient;
        mMediaXmlrpcClient = xmlrpcClient;
    }

    @Subscribe
    @Override
    public void onAction(Action action) {
        if (action.getType() == MediaAction.PUSH_MEDIA) {
            performPushMedia((MediaListPayload) action.getPayload());
        } else if (action.getType() == MediaAction.PUSHED_MEDIA) {
            MediaListPayload payload = (MediaListPayload) action.getPayload();
            handleMediaPushed(payload);
        } else if (action.getType() == MediaAction.UPLOAD_MEDIA) {
            performUploadMedia((UploadMediaPayload) action.getPayload());
        } else if (action.getType() == MediaAction.UPLOADED_MEDIA) {
            ProgressPayload payload = (ProgressPayload) action.getPayload();
            handleMediaUploaded(payload);
        } else if (action.getType() == MediaAction.FETCH_ALL_MEDIA) {
            performFetchAllMedia((MediaListPayload) action.getPayload());
        } else if (action.getType() == MediaAction.FETCH_MEDIA) {
            performFetchMedia((MediaListPayload) action.getPayload());
        } else if (action.getType() == MediaAction.FETCHED_MEDIA) {
            MediaListPayload payload = (MediaListPayload) action.getPayload();
            handleMediaFetched(payload.cause, payload);
        } else if (action.getType() == MediaAction.DELETE_MEDIA) {
            performDeleteMedia((MediaListPayload) action.getPayload());
        } else if (action.getType() == MediaAction.DELETED_MEDIA) {
            MediaListPayload payload = (MediaListPayload) action.getPayload();
            handleMediaDeleted(payload);
        } else if (action.getType() == MediaAction.UPDATE_MEDIA) {
            MediaListPayload payload = (MediaListPayload) action.getPayload();
            updateMedia(payload.media, true);
        } else if (action.getType() == MediaAction.REMOVE_MEDIA) {
            MediaListPayload payload = (MediaListPayload) action.getPayload();
            removeMedia(payload.media);
        }
    }

    @Override
    public void onRegister() {
    }

    public List<MediaModel> getAllSiteMedia(long siteId) {
        return MediaSqlUtils.getAllSiteMedia(siteId);
    }

    public int getSiteMediaCount(long siteId) {
        return getAllSiteMedia(siteId).size();
    }

    public boolean hasSiteMediaWithId(long siteId, long mediaId) {
        return getSiteMediaWithId(siteId, mediaId) != null;
    }

    public MediaModel getSiteMediaWithId(long siteId, long mediaId) {
        List<MediaModel> media = MediaSqlUtils.getSiteMediaWithId(siteId, mediaId);
        return media.size() > 0 ? media.get(0) : null;
    }

    public List<MediaModel> getSiteMediaWithIds(long siteId, List<Long> mediaIds) {
        return MediaSqlUtils.getSiteMediaWithIds(siteId, mediaIds);
    }

    public List<MediaModel> getSiteImages(long siteId) {
        return MediaSqlUtils.getSiteImages(siteId);
    }

    public int getSiteImageCount(long siteId) {
        return getSiteImages(siteId).size();
    }

    public List<MediaModel> getSiteImagesExcludingIds(long siteId, List<Long> filter) {
        return MediaSqlUtils.getSiteImagesExcluding(siteId, filter);
    }

    public List<MediaModel> getUnattachedSiteMedia(long siteId) {
        return MediaSqlUtils.matchSiteMedia(siteId, MediaModelTable.POST_ID, 0);
    }

    public int getUnattachedSiteMediaCount(long siteId) {
        return getUnattachedSiteMedia(siteId).size();
    }

    public List<MediaModel> getLocalSiteMedia(long siteId) {
        MediaModel.UploadState expectedState = MediaModel.UploadState.UPLOADED;
        return MediaSqlUtils.getSiteMediaExcluding(siteId, MediaModelTable.UPLOAD_STATE, expectedState);
    }

    public String getUrlForSiteVideoWithVideoPressGuid(long siteId, String videoPressGuid) {
        List<MediaModel> media =
                MediaSqlUtils.matchSiteMedia(siteId, MediaModelTable.VIDEO_PRESS_GUID, videoPressGuid);
        return media.size() > 0 ? media.get(0).getUrl() : null;
    }

    public String getThumbnailUrlForSiteMediaWithId(long siteId, long mediaId) {
        List<MediaModel> media = MediaSqlUtils.getSiteMediaWithId(siteId, mediaId);
        return media.size() > 0 ? media.get(0).getThumbnailUrl() : null;
    }

    public List<MediaModel> searchSiteMediaByTitle(long siteId, String titleSearch) {
        return MediaSqlUtils.searchSiteMedia(siteId, MediaModelTable.TITLE, titleSearch);
    }

    public MediaModel getPostMediaWithPath(long postId, String filePath) {
        List<MediaModel> media = MediaSqlUtils.matchPostMedia(postId, MediaModelTable.FILE_PATH, filePath);
        return media.size() > 0 ? media.get(0) : null;
    }

    public MediaModel getNextSiteMediaToDelete(long siteId) {
        List<MediaModel> media = MediaSqlUtils.matchSiteMedia(siteId,
                MediaModelTable.UPLOAD_STATE, MediaModel.UploadState.DELETE.toString());
        return media.size() > 0 ? media.get(0) : null;
    }

    public boolean hasSiteMediaToDelete(long siteId) {
        return getNextSiteMediaToDelete(siteId) != null;
    }

    //
    // Action implementations
    //

    private void updateMedia(List<MediaModel> media, boolean emit) {
        if (media == null || media.isEmpty()) return;

        OnMediaChanged event = new OnMediaChanged(MediaAction.UPDATE_MEDIA, new ArrayList<MediaModel>());
        for (MediaModel mediaItem : media) {
            if (MediaSqlUtils.insertOrUpdateMedia(mediaItem) > 0) {
                event.media.add(mediaItem);
            }
        }
        if (emit) emitChange(event);
    }

    private void removeMedia(List<MediaModel> media) {
        if (media == null || media.isEmpty()) return;

        OnMediaChanged event = new OnMediaChanged(MediaAction.REMOVE_MEDIA, new ArrayList<MediaModel>());
        for (MediaModel mediaItem : media) {
            if (MediaSqlUtils.deleteMedia(mediaItem) > 0) {
                event.media.add(mediaItem);
            }
        }
        emitChange(event);
    }

    //
    // Helper methods that choose the appropriate network client to perform an action
    //

    private void performPushMedia(MediaListPayload payload) {
        if (payload.media == null || payload.media.isEmpty() || payload.media.contains(null)) {
            // null or empty media list -or- list contains a null value
            notifyMediaError(MediaErrorType.NULL_MEDIA_ARG, MediaAction.PUSH_MEDIA, payload.media);
            return;
        }

        if (payload.site.isWPCom()) {
            mMediaRestClient.pushMedia(payload.site, payload.media);
        } else {
            mMediaXmlrpcClient.pushMedia(payload.site, payload.media);
        }
    }

    private void performUploadMedia(UploadMediaPayload payload) {
        if (payload.media == null) {
            // null or empty media list -or- list contains a null value
            notifyMediaError(MediaErrorType.NULL_MEDIA_ARG, MediaAction.UPLOAD_MEDIA, (MediaModel) null);
            return;
        } else if (!isWellFormedForUpload(payload.media)) {
            // list contained media items with insufficient data
            notifyMediaError(MediaErrorType.MALFORMED_MEDIA_ARG, MediaAction.UPLOAD_MEDIA, payload.media);
            return;
        }

        if (payload.site.isWPCom()) {
            mMediaRestClient.uploadMedia(payload.site, payload.media);
        } else {
            mMediaXmlrpcClient.uploadMedia(payload.site, payload.media);
        }
    }

    private void performFetchAllMedia(MediaListPayload payload) {
        if (payload.site.isWPCom()) {
            mMediaRestClient.fetchAllMedia(payload.site);
        } else {
            mMediaXmlrpcClient.fetchAllMedia(payload.site);
        }
    }

    private void performFetchMedia(MediaListPayload payload) {
        if (payload.media == null || payload.media.isEmpty() || payload.media.contains(null)) {
            // null or empty media list -or- list contains a null value
            notifyMediaError(MediaErrorType.NULL_MEDIA_ARG, MediaAction.FETCH_MEDIA, payload.media);
            return;
        }

        if (payload.site.isWPCom()) {
            mMediaRestClient.fetchMedia(payload.site, payload.media);
        } else {
            mMediaXmlrpcClient.fetchMedia(payload.site, payload.media);
        }
    }

    private void performDeleteMedia(@NonNull MediaListPayload payload) {
        if (payload.media == null || payload.media.isEmpty() || payload.media.contains(null)) {
            notifyMediaError(MediaErrorType.NULL_MEDIA_ARG, MediaAction.DELETE_MEDIA, payload.media);
            return;
        }

        if (payload.site.isWPCom()) {
            mMediaRestClient.deleteMedia(payload.site, payload.media);
        } else {
            mMediaXmlrpcClient.deleteMedia(payload.site, payload.media);
        }
    }

    private void handleMediaUploaded(@NonNull ProgressPayload payload) {
        OnMediaUploaded onMediaUploaded = new OnMediaUploaded(payload.media, payload.progress, payload.completed);
        onMediaUploaded.error = payload.error;
        emitChange(onMediaUploaded);
    }

    private void handleMediaPushed(@NonNull MediaListPayload payload) {
        OnMediaChanged onMediaChanged = new OnMediaChanged(MediaAction.PUSH_MEDIA, payload.media);
        onMediaChanged.error = payload.error;
        emitChange(onMediaChanged);
    }

    private void handleMediaFetched(MediaAction cause, @NonNull MediaListPayload payload) {
        OnMediaChanged onMediaChanged = new OnMediaChanged(cause, payload.media);

        if (payload.isError()) {
            onMediaChanged.error = payload.error;
        } else if (payload.media != null && !payload.media.isEmpty()) {
            for (MediaModel media : payload.media) {
                MediaSqlUtils.insertOrUpdateMedia(media);
            }
        }

        emitChange(onMediaChanged);
    }

    private void handleMediaDeleted(@NonNull MediaListPayload payload) {
        OnMediaChanged onMediaChanged = new OnMediaChanged(MediaAction.DELETED_MEDIA, payload.media);

        if (payload.isError()) {
            onMediaChanged.error = payload.error;
        } else if (payload.media != null && !payload.media.isEmpty()) {
            for (MediaModel media : payload.media) {
                MediaSqlUtils.deleteMedia(media);
            }
        }

        emitChange(onMediaChanged);
    }

    private boolean isWellFormedForUpload(@NonNull MediaModel media) {
        return BaseUploadRequestBody.hasRequiredData(media) == null;
    }

    private void notifyMediaError(MediaErrorType errorType, MediaAction cause, List<MediaModel> media) {
        OnMediaChanged mediaChange = new OnMediaChanged(cause, media);
        mediaChange.error = new MediaError(errorType);
        emitChange(mediaChange);
    }

    private void notifyMediaError(MediaErrorType errorType, MediaAction cause, MediaModel media) {
        List<MediaModel> mediaList = new ArrayList<>();
        mediaList.add(media);
        notifyMediaError(errorType, cause, mediaList);
    }
}
