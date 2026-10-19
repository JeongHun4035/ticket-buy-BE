package com.ticketing.ticketbuybe.domain.catalog;

import com.ticketing.ticketbuybe.global.error.ApiError;
import com.ticketing.ticketbuybe.infrastructure.persistence.Store;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Immutable images in the same DB transaction as event creation; no orphan files after rollback. */
@Service
public class EventImages {
    static final int MAX_BYTES=5*1024*1024;
    final Store s;final String publicBase;
    public EventImages(Store s,@Value("${app.public-base-url:http://localhost:8080}") String publicBase) {
        this.s=s;this.publicBase=publicBase.replaceAll("/+$","");
        if(!this.publicBase.matches("https?://[^\\s?#]+")) throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTP(S) base URL");
    }
    public String url(String path) {return path!=null&&path.startsWith("/")?publicBase+path:path;}
    public record ImageData(byte[] bytes,String contentType) {}
    ImageData validate(MultipartFile image) {
        if(image==null||image.isEmpty()) throw new ApiError(400,"IMAGE_REQUIRED","행사 이미지를 첨부하세요.");
        if(image.getSize()>MAX_BYTES) throw new ApiError(413,"IMAGE_TOO_LARGE","이미지는 최대 5MiB입니다.");
        if(!Set.of("image/png","image/jpeg").contains(Objects.toString(image.getContentType(),"")))
            throw new ApiError(415,"UNSUPPORTED_IMAGE_TYPE","PNG 또는 JPEG 이미지를 사용하세요.");
        try(var input=new MemoryCacheImageInputStream(image.getInputStream())) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new ApiError(400,"INVALID_IMAGE","유효한 이미지 파일이 아닙니다.");
            var reader=readers.next();
            try {
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime=format.equals("png")?"image/png":Set.of("jpeg","jpg").contains(format)?"image/jpeg":null;
                if(mime==null||!mime.equals(image.getContentType())) throw new ApiError(415,"UNSUPPORTED_IMAGE_TYPE","실제 이미지 형식과 Content-Type을 확인하세요.");
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>6000||height>6000||(long)width*height>20000000)
                    throw new ApiError(400,"IMAGE_DIMENSIONS_EXCEEDED","이미지 각 변은 최대 6000px, 총 2000만 픽셀입니다.");
                var decoded=reader.read(0);var output=new ByteArrayOutputStream();
                // Re-encode to strip metadata and trailing payloads. Client filenames are never used.
                if(!ImageIO.write(decoded,mime.equals("image/png")?"png":"jpeg",output)) throw new ApiError(400,"INVALID_IMAGE","이미지를 처리할 수 없습니다.");
                if(output.size()>MAX_BYTES) throw new ApiError(413,"IMAGE_TOO_LARGE","처리된 이미지가 5MiB를 초과합니다.");
                return new ImageData(output.toByteArray(),mime);
            } finally {reader.dispose();}
        } catch(IOException e) {throw new ApiError(400,"INVALID_IMAGE","이미지 파일을 읽을 수 없습니다.");}
    }
    String save(ImageData image) {
        String id=Store.id();s.jdbc().update("insert into event_image(id,content_type,data,created_at) values(?,?,?,?)",id,image.contentType(),image.bytes(),Store.utc(Instant.now()));
        return "/api/v1/event-images/"+id;
    }
    public ImageData get(String id) {
        var row=s.one("select content_type,data from event_image where id=?",id);
        return new ImageData((byte[])row.get("data"),row.get("content_type").toString());
    }
}
