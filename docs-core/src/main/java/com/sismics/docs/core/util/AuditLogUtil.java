package com.sismics.docs.core.util;

import javax.persistence.EntityManager;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.jpa.AuditLogDao;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.model.jpa.Loggable;
import com.sismics.util.context.ThreadLocalContext;

/**
 * Audit log utilities.
 * 
 * @author bgamard
 */
public class AuditLogUtil {
    /**
     * Create an audit log.
     * 
     * @param entity Entity
     * @param type Audit log type
     */
    public static void create(Loggable loggable, AuditLogType type, String userId) {
        // Get the entity ID
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        String entityId = (String) em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(loggable);
        
        // Create the audit log
        AuditLogDao auditLogDao = new AuditLogDao();
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setEntityId(entityId);
        auditLog.setEntityClass(loggable.getClass().getSimpleName());
        auditLog.setType(type);
        auditLog.setMessage(loggable.toMessage());
        auditLogDao.create(auditLog);
    }
}
