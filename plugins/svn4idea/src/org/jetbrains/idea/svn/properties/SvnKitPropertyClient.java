package org.jetbrains.idea.svn.properties;

import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.util.LineSeparator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.api.BaseSvnClient;
import org.jetbrains.idea.svn.api.Depth;
import org.jetbrains.idea.svn.commandLine.SvnBindException;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.wc.*;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import java.io.File;
import java.util.Map;

/**
 * @author Konstantin Kolosovsky.
 */
public class SvnKitPropertyClient extends BaseSvnClient implements PropertyClient {

  public static final ISVNOptions LF_SEPARATOR_OPTIONS = new DefaultSVNOptions() {
    @Override
    public byte[] getNativeEOL() {
      return CharsetToolkit.getUtf8Bytes(LineSeparator.LF.getSeparatorString());
    }
  };

  @Nullable
  @Override
  public PropertyData getProperty(@NotNull SvnTarget target,
                                  @NotNull String property,
                                  boolean revisionProperty,
                                  @Nullable SVNRevision revision) throws VcsException {
    try {
      if (!revisionProperty) {
        if (target.isFile()) {
          return PropertyData.create(createClient().doGetProperty(target.getFile(), property, target.getPegRevision(), revision));
        } else {
          return PropertyData.create(createClient().doGetProperty(target.getURL(), property, target.getPegRevision(), revision));
        }
      } else {
        return getRevisionProperty(target, property, revision);
      }
    }
    catch (SVNException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  private SVNWCClient createClient() {
    SVNWCClient client = myVcs.getSvnKitManager().createWCClient();
    client.setOptions(LF_SEPARATOR_OPTIONS);

    return client;
  }

  @Override
  public void getProperty(@NotNull SvnTarget target,
                          @NotNull String property,
                          @Nullable SVNRevision revision,
                          @Nullable Depth depth,
                          @Nullable PropertyConsumer handler) throws VcsException {
    runGetProperty(target, property, revision, depth, handler);
  }

  @Override
  public void list(@NotNull SvnTarget target,
                   @Nullable SVNRevision revision,
                   @Nullable Depth depth,
                   @Nullable PropertyConsumer handler) throws VcsException {
    runGetProperty(target, null, revision, depth, handler);
  }

  @Override
  public void setProperty(@NotNull File file,
                          @NotNull String property,
                          @Nullable SVNPropertyValue value,
                          @Nullable Depth depth,
                          boolean force) throws VcsException {
    try {
      createClient().doSetProperty(file, property, value, force, toDepth(depth), null, null);
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public void setProperties(@NotNull File file, @NotNull PropertiesMap properties) throws VcsException {
    final SVNProperties propertiesToSet = toSvnProperties(properties);
    try {
      createClient().doSetProperty(file, new ISVNPropertyValueProvider() {
        @Override
        public SVNProperties providePropertyValues(File path, SVNProperties properties) throws SVNException {
          return propertiesToSet;
        }
      }, true, SVNDepth.EMPTY, null, null);
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @Override
  public void setRevisionProperty(@NotNull SvnTarget target,
                                  @NotNull String property,
                                  @NotNull SVNRevision revision,
                                  @Nullable SVNPropertyValue value,
                                  boolean force) throws VcsException {
    try {
      if (target.isFile()) {
        createClient().doSetRevisionProperty(target.getFile(), revision, property, value, force, null);
      }
      else {
        createClient().doSetRevisionProperty(target.getURL(), revision, property, value, force, null);
      }
    }
    catch (SVNException e) {
      throw new SvnBindException(e);
    }
  }

  @NotNull
  private static SVNProperties toSvnProperties(@NotNull PropertiesMap properties) {
    SVNProperties result = new SVNProperties();

    for (Map.Entry<String, SVNPropertyValue> entry : properties.entrySet()) {
      result.put(entry.getKey(), entry.getValue());
    }

    return result;
  }

  private void runGetProperty(@NotNull SvnTarget target,
                              @Nullable String property,
                              @Nullable SVNRevision revision,
                              @Nullable Depth depth,
                              @Nullable PropertyConsumer handler) throws VcsException {
    SVNWCClient client = createClient();

    try {
      if (target.isURL()) {
        client.doGetProperty(target.getURL(), property, target.getPegRevision(), revision, toDepth(depth), toHandler(handler));
      } else {
        client.doGetProperty(target.getFile(), property, target.getPegRevision(), revision, toDepth(depth), toHandler(handler), null);
      }
    } catch (SVNException e) {
      throw new VcsException(e);
    }
  }

  private PropertyData getRevisionProperty(@NotNull SvnTarget target, @NotNull final String property, @Nullable SVNRevision revision)
    throws SVNException {
    final SVNWCClient client = createClient();
    final PropertyData[] result = new PropertyData[1];
    ISVNPropertyHandler handler = new ISVNPropertyHandler() {
      @Override
      public void handleProperty(File path, SVNPropertyData property) throws SVNException {
        handle(property);
      }

      @Override
      public void handleProperty(SVNURL url, SVNPropertyData property) throws SVNException {
        handle(property);
      }

      @Override
      public void handleProperty(long revision, SVNPropertyData property) throws SVNException {
        handle(property);
      }

      private void handle(@NotNull SVNPropertyData data) {
        if (property.equals(data.getName())) {
          result[0] = PropertyData.create(data);
        }
      }
    };

    if (target.isFile()) {
      client.doGetRevisionProperty(target.getFile(), null, revision, handler);
    } else {
      client.doGetRevisionProperty(target.getURL(), null, revision, handler);
    }

    return result[0];
  }

  @Nullable
  private static ISVNPropertyHandler toHandler(@Nullable final PropertyConsumer consumer) {
    ISVNPropertyHandler result = null;

    if (consumer != null) {
      result = new ISVNPropertyHandler() {
        @Override
        public void handleProperty(File path, SVNPropertyData property) throws SVNException {
          consumer.handleProperty(path, PropertyData.create(property));
        }

        @Override
        public void handleProperty(SVNURL url, SVNPropertyData property) throws SVNException {
          consumer.handleProperty(url, PropertyData.create(property));
        }

        @Override
        public void handleProperty(long revision, SVNPropertyData property) throws SVNException {
          consumer.handleProperty(revision, PropertyData.create(property));
        }
      };
    }

    return result;
  }
}
