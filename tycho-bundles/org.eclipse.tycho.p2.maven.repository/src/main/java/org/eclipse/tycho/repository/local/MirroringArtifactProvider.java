/*******************************************************************************
 * Copyright (c) 2012, 2013 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Tobias Oberlies (SAP AG) - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.repository.local;

import static org.eclipse.tycho.repository.util.internal.BundleConstants.BUNDLE_ID;

import java.io.File;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.query.CompoundQueryable;
import org.eclipse.equinox.p2.query.IQuery;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.repository.artifact.IArtifactDescriptor;
import org.eclipse.tycho.core.facade.MavenLogger;
import org.eclipse.tycho.repository.p2base.artifact.provider.IRawArtifactFileProvider;
import org.eclipse.tycho.repository.p2base.artifact.provider.IRawArtifactProvider;
import org.eclipse.tycho.repository.p2base.artifact.provider.streaming.ArtifactSinkException;
import org.eclipse.tycho.repository.p2base.artifact.provider.streaming.IArtifactSink;
import org.eclipse.tycho.repository.p2base.artifact.provider.streaming.IRawArtifactSink;
import org.eclipse.tycho.repository.util.StatusTool;

/**
 * {@link IRawArtifactFileProvider} which caches all accessed artifacts in the local Maven
 * repository.
 * 
 * <p>
 * Note that a <tt>MirroringArtifactProvider</tt> is not a transparent cache of the remote
 * providers. The content provided by this instance differs from the remote providers' content in
 * the following ways:
 * <ul>
 * <li>This instance provides all content in the local Maven repository (previously cached or
 * installed) in addition to the remote content. This allows lazy access to the remote repositories
 * (bug 347477).</li>
 * <li>This instance only provides the remote artifacts in certain formats, i.e. only the canonical
 * format, or the canonical format and the packed format.</li>
 * </ul>
 * </p>
 */
public class MirroringArtifactProvider implements IRawArtifactFileProvider {

    private MavenLogger logger;

    private IRawArtifactProvider remoteProviders;
    private LocalArtifactRepository localArtifactRepository;

    private final IProgressMonitor monitor = null; // TODO log via progress monitor (so that the remote URL is shown)?

    /**
     * Creates a new {@link MirroringArtifactProvider} instance.
     * 
     * @param localArtifactRepository
     *            The local Maven repository
     * @param remoteProviders
     *            The provider that will be queried by this instance when it is asked for an
     *            artifact which is not (yet) available in the local Maven repository. Typically
     *            this provider is backed by remote p2 repositories.
     * @param logger
     *            a logger for progress output
     */
    public static MirroringArtifactProvider createInstance(LocalArtifactRepository localArtifactRepository,
            IRawArtifactProvider remoteProviders, MavenLogger logger) {
        return new MirroringArtifactProvider(localArtifactRepository, remoteProviders, logger);
    }

    private MirroringArtifactProvider(LocalArtifactRepository localArtifactRepository,
            IRawArtifactProvider remoteProviders, MavenLogger logger) {
        this.remoteProviders = remoteProviders;
        this.localArtifactRepository = localArtifactRepository;
        this.logger = logger;
    }

    // pass through methods

    public final boolean contains(IArtifactKey key) {
        if (localArtifactRepository.contains(key)) {
            return true;
        }
        return remoteProviders.contains(key);
    }

    @SuppressWarnings({ "restriction", "unchecked" })
    public final IQueryResult<IArtifactKey> query(IQuery<IArtifactKey> query, IProgressMonitor monitor) {
        IQueryable<IArtifactKey>[] sources = new IQueryable[] { localArtifactRepository, remoteProviders };
        return new CompoundQueryable<IArtifactKey>(sources).query(query, nonNull(monitor));
    }

    // mirroring methods

    public final File getArtifactFile(IArtifactKey key) throws MirroringFailedException {
        if (makeLocallyAvailable(key)) {
            return localArtifactRepository.getArtifactFile(key);
        }
        return null;
    }

    public final File getArtifactFile(IArtifactDescriptor descriptor) throws MirroringFailedException {
        if (makeLocallyAvailable(descriptor.getArtifactKey())) {
            return localArtifactRepository.getArtifactFile(descriptor);
        }
        return null;
    }

    public final IStatus getArtifact(IArtifactSink sink, IProgressMonitor monitor) throws ArtifactSinkException,
            MirroringFailedException {
        IArtifactKey requestedKey = sink.getArtifactToBeWritten();
        if (makeLocallyAvailable(requestedKey)) {
            return localArtifactRepository.getArtifact(sink, monitor);
        }
        return artifactNotFoundStatus(requestedKey);
    }

    public final IStatus getRawArtifact(IRawArtifactSink sink, IProgressMonitor monitor) throws ArtifactSinkException,
            MirroringFailedException {
        IArtifactKey requestedKey = sink.getArtifactToBeWritten();
        if (makeLocallyAvailable(requestedKey)) {
            return localArtifactRepository.getRawArtifact(sink, monitor);
        }
        return artifactNotFoundStatus(requestedKey);
    }

    public final IArtifactDescriptor[] getArtifactDescriptors(IArtifactKey key) throws MirroringFailedException {
        if (makeLocallyAvailable(key)) {
            return localArtifactRepository.getArtifactDescriptors(key);
        }
        return new IArtifactDescriptor[0];
    }

    public final boolean contains(IArtifactDescriptor descriptor) throws MirroringFailedException {
        if (makeLocallyAvailable(descriptor.getArtifactKey())) {
            return localArtifactRepository.contains(descriptor);
        }
        return false;
    }

    /**
     * Downloads the artifact from remote if it isn't available locally yet.
     * 
     * @return <code>false</code> if the artifact is neither already cached locally nor available
     *         remotely.
     * @throws MirroringFailedException
     *             if a fatal error occurred while downloading the artifact.
     */
    private boolean makeLocallyAvailable(IArtifactKey key) throws MirroringFailedException {
        // TODO mirror raw artifacts if enabled
        if (localArtifactRepository.contains(key)) {
            return true;
        } else if (remoteProviders.contains(key)) {
            downloadArtifact(key);
            return true;
        } else {
            return false;
        }
    }

    private void downloadArtifact(IArtifactKey key) throws MirroringFailedException {
        try {
            // TODO log via progress monitor (so that the remote URL is shown)?
            logger.info("Downloading " + key.getId() + "_" + key.getVersion() + "...");

            IArtifactSink localSink = localArtifactRepository.newAddingArtifactSink(key);
            IStatus transferStatus = remoteProviders.getArtifact(localSink, null);

            if (transferStatus.matches(IStatus.ERROR | IStatus.CANCEL)) {
                // TODO 393004 better formatted, additional log output
                throw new MirroringFailedException("Could not mirror artifact " + key
                        + " into the local Maven repository: " + StatusTool.collectProblems(transferStatus),
                        StatusTool.findException(transferStatus));
            } else if (transferStatus.matches(IStatus.WARNING)) {
                logger.warn(StatusTool.collectProblems(transferStatus));
            }

        } catch (ProvisionException e) {
            throw new MirroringFailedException("Error while mirroring artifact " + key
                    + " into the local Maven repository" + e.getMessage(), e);
        } catch (ArtifactSinkException e) {
            throw new MirroringFailedException("Error while mirroring artifact " + key
                    + " into the local Maven repository" + e.getMessage(), e);
        }
    }

    private static IStatus artifactNotFoundStatus(IArtifactKey key) {
        return new Status(IStatus.ERROR, BUNDLE_ID, ProvisionException.ARTIFACT_NOT_FOUND, "Artifact " + key
                + " is neither available in the local Maven repository nor in the configured remote repositories", null);
    }

    // TODO share?
    private static IProgressMonitor nonNull(IProgressMonitor monitor) {
        if (monitor == null)
            return new NullProgressMonitor();
        else
            return monitor;
    }

    public static class MirroringFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MirroringFailedException(String message, Throwable cause) {
            super(message, cause);
        }

    }
}
