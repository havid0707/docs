package com.sismics.docs.rest.resource;

import com.google.common.collect.Lists;
import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.jpa.*;
import com.sismics.docs.core.dao.jpa.criteria.GroupCriteria;
import com.sismics.docs.core.dao.jpa.criteria.UserCriteria;
import com.sismics.docs.core.dao.jpa.dto.GroupDto;
import com.sismics.docs.core.dao.jpa.dto.UserDto;
import com.sismics.docs.core.model.jpa.*;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.text.MessageFormat;
import java.util.List;

/**
 * ACL REST resources.
 * 
 * @author bgamard
 */
@Path("/acl")
public class AclResource extends BaseResource {
    /**
     * Add an ACL.
     *
     * @api {put} /acl Add an ACL
     * @apiName PutAcl
     * @apiGroup Acl
     * @apiParam {String} source Source ID
     * @apiParam {String="READ","WRITE"} perm Permission
     * @apiParam {String} target Target ID
     * @apiParam {String="USER","GROUP","SHARE"} type Target type
     * @apiSuccess {String} id Acl ID
     * @apiSuccess {String} perm Permission
     * @apiSuccess {String} name Target name
     * @apiSuccess {String="USER","GROUP","SHARE"} type Target type
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) InvalidTarget This target does not exist
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param sourceId Source ID
     * @param permStr Permission
     * @param targetName Target name
     * @param typeStr ACL type
     * @return Response
     */
    @PUT
    public Response add(@FormParam("source") String sourceId,
            @FormParam("perm") String permStr,
            @FormParam("target") String targetName,
            @FormParam("type") String typeStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input
        ValidationUtil.validateRequired(sourceId, "source");
        PermType perm = PermType.valueOf(ValidationUtil.validateLength(permStr, "perm", 1, 30, false));
        AclTargetType type = AclTargetType.valueOf(ValidationUtil.validateLength(typeStr, "type", 1, 10, false));
        targetName = ValidationUtil.validateLength(targetName, "target", 1, 50, false);
        
        // Search user or group
        String targetId = null;
        switch (type) {
        case USER:
            UserDao userDao = new UserDao();
            User user = userDao.getActiveByUsername(targetName);
            if (user != null) {
                targetId = user.getId();
            }
            break;
        case GROUP:
            GroupDao groupDao = new GroupDao();
            Group group = groupDao.getActiveByName(targetName);
            if (group != null) {
                targetId = group.getId();
            }
            break;
        case SHARE:
            // Share must use the Share REST resource
            break;
        }
        
        // Does a target has been found?
        if (targetId == null) {
            throw new ClientException("InvalidTarget", MessageFormat.format("This target does not exist: {0}", targetName));
        }
        
        // Check permission on the source by the principal
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(sourceId, PermType.WRITE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }
        
        // Create the ACL
        Acl acl = new Acl();
        acl.setSourceId(sourceId);
        acl.setPerm(perm);
        acl.setTargetId(targetId);
        
        // Avoid duplicates
        if (!aclDao.checkPermission(acl.getSourceId(), acl.getPerm(), Lists.newArrayList(acl.getTargetId()))) {
            aclDao.create(acl, principal.getId());
            
            // Returns the ACL
            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("perm", acl.getPerm().name())
                    .add("id", acl.getTargetId())
                    .add("name", targetName)
                    .add("type", type.name());
            return Response.ok().entity(response.build()).build();
        }
        
        return Response.ok().entity(Json.createObjectBuilder().build()).build();
    }
    
    /**
     * Deletes an ACL.
     *
     * @api {delete} /acl/:source/:perm/:target Delete an ACL
     * @apiName DeleteAcl
     * @apiGroup Acl
     * @apiParam {String} source Source ID
     * @apiParam {String="READ","WRITE"} perm Permission
     * @apiParam {String} target Target ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) AclError Cannot delete base ACL on a document or a tag
     * @apiPermission user
     * @apiVersion 1.5.0
     * 
     * @param sourceId Source ID
     * @param permStr Permission
     * @param targetId Target ID
     * @return Response
     */
    @DELETE
    @Path("{sourceId: [a-z0-9\\-]+}/{perm: [A-Z]+}/{targetId: [a-z0-9\\-]+}")
    public Response delete(
            @PathParam("sourceId") String sourceId,
            @PathParam("perm") String permStr,
            @PathParam("targetId") String targetId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input
        ValidationUtil.validateRequired(sourceId, "source");
        PermType perm = PermType.valueOf(ValidationUtil.validateLength(permStr, "perm", 1, 30, false));
        ValidationUtil.validateRequired(targetId, "target");

        // Check permission on the source by the principal
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(sourceId, PermType.WRITE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }
        
        // Cannot delete R/W on a source document if the target is the creator
        DocumentDao documentDao = new DocumentDao();
        Document document = documentDao.getById(sourceId);
        if (document != null && document.getUserId().equals(targetId)) {
            throw new ClientException("AclError", "Cannot delete base ACL on a document");
        }

        // Cannot delete R/W on a source tag if the target is the creator
        TagDao tagDao = new TagDao();
        Tag tag = tagDao.getById(sourceId);
        if (tag != null && tag.getUserId().equals(targetId)) {
            throw new ClientException("AclError", "Cannot delete base ACL on a tag");
        }

        // Delete the ACL
        aclDao.delete(sourceId, perm, targetId, principal.getId());
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Search possible ACL target.
     *
     * @api {get} /acl/target/search Search in ACL targets
     * @apiName GetAclTargetSearch
     * @apiGroup Acl
     * @apiParam {String} search Search query
     * @apiSuccess {Object[]} users List of users
     * @apiSuccess {String} users.name Username
     * @apiSuccess {Object[]} groups List of groups
     * @apiSuccess {String} groups.name Group name
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     * 
     * @param search Search query
     * @return Response
     */
    @GET
    @Path("target/search")
    public Response targetList(@QueryParam("search") String search) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input
        search = ValidationUtil.validateLength(search, "search", 1, 50, false);
        
        // Search users
        UserDao userDao = new UserDao();
        JsonArrayBuilder users = Json.createArrayBuilder();
        SortCriteria sortCriteria = new SortCriteria(1, true);
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setSearch(search), sortCriteria);
        for (UserDto userDto : userDtoList) {
            users.add(Json.createObjectBuilder()
                    .add("name", userDto.getUsername()));
        }
        
        // Search groups
        GroupDao groupDao = new GroupDao();
        JsonArrayBuilder groups = Json.createArrayBuilder();
        List<GroupDto> groupDtoList = groupDao.findByCriteria(new GroupCriteria().setSearch(search), sortCriteria);
        for (GroupDto groupDto : groupDtoList) {
            groups.add(Json.createObjectBuilder()
                    .add("name", groupDto.getName()));
        }
        
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("users", users)
                .add("groups", groups);
        return Response.ok().entity(response.build()).build();
    }
}
