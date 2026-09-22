package com.icepark.repository;

import com.icepark.entity.Session;
import com.icepark.enums.SessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findBySessionCode(String sessionCode);

    List<Session> findByStatus(SessionStatus status);

    boolean existsBySessionCode(String sessionCode);

    /**
     * 发装/归还/结束场次都先对场次行加悲观写锁，把同一场次的状态变更串行化：
     * 既解决同一器材的并发领用，也杜绝"发装进行中、场次恰好被结束"的竞态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") Long id);

    /**
     * 送检器材时按ID升序锁定它所绑定的全部场次行，与发装/归还/结束路径使用相同的锁层级。
     * 调用方必须先把ID排序，保证多场次之间也按固定顺序加锁、不会交叉死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id IN :ids ORDER BY s.id ASC")
    List<Session> findByIdsForUpdate(@Param("ids") List<Long> ids);
}