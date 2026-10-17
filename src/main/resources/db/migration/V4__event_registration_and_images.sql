CREATE TABLE id_allocator (name VARCHAR(30) PRIMARY KEY, next_id BIGINT NOT NULL);
INSERT INTO id_allocator(name,next_id)
 SELECT 'event',CASE WHEN COALESCE(MAX(id),0)>=100000 THEN MAX(id)+1 ELSE 100000 END FROM event;
INSERT INTO id_allocator(name,next_id)
 SELECT 'performance',CASE WHEN COALESCE(MAX(id),0)>=100000 THEN MAX(id)+1 ELSE 100000 END FROM performance;
CREATE TABLE event_image (
 id VARCHAR(36) PRIMARY KEY, content_type VARCHAR(30) NOT NULL,
 data BYTEA NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
