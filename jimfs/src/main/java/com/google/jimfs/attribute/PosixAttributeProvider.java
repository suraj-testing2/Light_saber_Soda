/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.attribute;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.jimfs.attribute.UserPrincipals.createGroupPrincipal;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Attribute provider that provides the {@link PosixFileAttributeView} ("posix") and allows reading
 * of {@link PosixFileAttributes}.
 *
 * @author Colin Decker
 */
final class PosixAttributeProvider extends AttributeProvider<PosixFileAttributeView> {

  private static final ImmutableSet<String> ATTRIBUTES = ImmutableSet.of(
      "group",
      "permissions");

  private static final ImmutableSet<String> INHERITED_VIEWS = ImmutableSet.of("basic", "owner");

  private static final GroupPrincipal DEFAULT_GROUP = createGroupPrincipal("group");
  private static final ImmutableSet<PosixFilePermission> DEFAULT_PERMISSIONS =
      Sets.immutableEnumSet(PosixFilePermissions.fromString("rw-r--r--"));

  @Override
  public String name() {
    return "posix";
  }

  @Override
  public ImmutableSet<String> inherits() {
    return INHERITED_VIEWS;
  }

  @Override
  public ImmutableSet<String> fixedAttributes() {
    return ATTRIBUTES;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Map<String, ?> defaultValues(Map<String, ?> userProvidedDefaults) {
    Object userProvidedGroup = userProvidedDefaults.get("posix:group");

    UserPrincipal group = DEFAULT_GROUP;
    if (userProvidedGroup != null) {
      if (userProvidedGroup instanceof String) {
        group = createGroupPrincipal((String) userProvidedGroup);
      } else if (userProvidedGroup instanceof GroupPrincipal) {
        group = createGroupPrincipal(((GroupPrincipal) userProvidedGroup).getName());
      } else {
        throw new IllegalArgumentException("invalid type " + userProvidedGroup.getClass()
            + " for attribute 'posix:group': should be one of " + String.class + " or "
            + GroupPrincipal.class);
      }
    }

    Object userProvidedPermissions = userProvidedDefaults.get("posix:permissions");

    Set<PosixFilePermission> permissions = DEFAULT_PERMISSIONS;
    if (userProvidedPermissions != null) {
      if (userProvidedPermissions instanceof String) {
        permissions = Sets.immutableEnumSet(
            PosixFilePermissions.fromString((String) userProvidedPermissions));
      } else if (userProvidedPermissions instanceof Set) {
        permissions = toPermissions((Set<?>) userProvidedPermissions);
      } else {
        throw new IllegalArgumentException("invalid type " + userProvidedPermissions.getClass()
            + " for attribute 'posix:permissions': should be one of " + String.class + " or "
            + Set.class);
      }
    }

    return ImmutableMap.of(
        "posix:group", group,
        "posix:permissions", permissions);
  }

  @Nullable
  @Override
  public Object get(Inode inode, String attribute) {
    switch (attribute) {
      case "group":
        return inode.getAttribute("posix:group");
      case "permissions":
        return inode.getAttribute("posix:permissions");
    }
    return null;
  }

  @Override
  public void set(Inode inode, String view, String attribute, Object value,
      boolean create) {
    switch (attribute) {
      case "group":
        checkNotCreate(view, attribute, create);

        GroupPrincipal group = checkType(view, attribute, value, GroupPrincipal.class);
        if (!(group instanceof UserPrincipals.JimfsGroupPrincipal)) {
          group = UserPrincipals.createGroupPrincipal(group.getName());
        }
        inode.setAttribute("posix:group", group);
        break;
      case "permissions":
        inode.setAttribute("posix:permissions",
            toPermissions(checkType(view, attribute, value, Set.class)));
    }
  }

  @SuppressWarnings("unchecked")
  private static ImmutableSet<PosixFilePermission> toPermissions(Set<?> set) {
    for (Object obj : set) {
      checkNotNull(obj);
      if (!(obj instanceof PosixFilePermission)) {
        throw new IllegalArgumentException("invalid element for attribute 'posix:permissions': "
            + "should be Set<PosixFilePermission>, found element of type " + obj.getClass());
      }
    }

    return Sets.immutableEnumSet((Set<PosixFilePermission>) set);
  }

  @Override
  public Class<PosixFileAttributeView> viewType() {
    return PosixFileAttributeView.class;
  }

  @Override
  public PosixFileAttributeView view(Inode.Lookup lookup,
      Map<String, FileAttributeView> inheritedViews) {
    return new View(lookup,
        (BasicFileAttributeView) inheritedViews.get("basic"),
        (FileOwnerAttributeView) inheritedViews.get("owner"));
  }

  @Override
  public Class<PosixFileAttributes> attributesType() {
    return PosixFileAttributes.class;
  }

  @Override
  public PosixFileAttributes readAttributes(Inode inode) {
    return new Attributes(inode);
  }

  /**
   * Implementation of {@link PosixFileAttributeView}.
   */
  private static class View extends AbstractAttributeView implements PosixFileAttributeView {

    private final BasicFileAttributeView basicView;
    private final FileOwnerAttributeView ownerView;

    protected View(Inode.Lookup lookup,
        BasicFileAttributeView basicView, FileOwnerAttributeView ownerView) {
      super(lookup);
      this.basicView = checkNotNull(basicView);
      this.ownerView = checkNotNull(ownerView);
    }

    @Override
    public String name() {
      return "posix";
    }

    @Override
    public PosixFileAttributes readAttributes() throws IOException {
      return new Attributes(lookupInode());
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime)
        throws IOException {
      basicView.setTimes(lastModifiedTime, lastAccessTime, createTime);
    }

    @Override
    public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
      lookupInode().setAttribute("posix:permissions", ImmutableSet.copyOf(perms));
    }

    @Override
    public void setGroup(GroupPrincipal group) throws IOException {
      lookupInode().setAttribute("posix:group", checkNotNull(group));
    }

    @Override
    public UserPrincipal getOwner() throws IOException {
      return ownerView.getOwner();
    }

    @Override
    public void setOwner(UserPrincipal owner) throws IOException {
      ownerView.setOwner(owner);
    }
  }

  /**
   * Implementation of {@link PosixFileAttributes}.
   */
  static class Attributes extends BasicAttributeProvider.Attributes implements PosixFileAttributes {

    private final UserPrincipal owner;
    private final GroupPrincipal group;
    private final ImmutableSet<PosixFilePermission> permissions;

    @SuppressWarnings("unchecked")
    protected Attributes(Inode inode) {
      super(inode);
      this.owner = inode.getAttribute("owner:owner");
      this.group = inode.getAttribute("posix:group");
      this.permissions = inode.getAttribute("posix:permissions");
    }

    @Override
    public UserPrincipal owner() {
      return owner;
    }

    @Override
    public GroupPrincipal group() {
      return group;
    }

    @Override
    public ImmutableSet<PosixFilePermission> permissions() {
      return permissions;
    }
  }
}