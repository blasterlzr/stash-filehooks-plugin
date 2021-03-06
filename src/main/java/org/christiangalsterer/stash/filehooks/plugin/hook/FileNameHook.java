package org.christiangalsterer.stash.filehooks.plugin.hook;

import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.hook.HookResponse;
import com.atlassian.bitbucket.hook.repository.PreReceiveRepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookContext;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.setting.RepositorySettingsValidator;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.pageobjects.elements.Option;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.*;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.christiangalsterer.stash.filehooks.plugin.hook.Predicates.*;
import static com.google.common.collect.Iterables.filter;

/**
 * Checks the name and path of a file in the pre-receive phase and rejects the push when the changeset contains files which match the configured file name pattern.
 */
public class FileNameHook implements PreReceiveRepositoryHook, RepositorySettingsValidator {

    private static final String SETTINGS_INCLUDE_PATTERN = "pattern";
    private static final String SETTINGS_EXCLUDE_PATTERN = "pattern-exclude";
    private static final String SETTINGS_AFFECTED_BRANCHES = "affected-branches";

    private final ChangesetService changesetService;
    private final I18nService i18n;

    public FileNameHook(ChangesetService changesetService, I18nService i18n) {
        this.changesetService = changesetService;
        this.i18n = i18n;
    }

    @Override
    public boolean onReceive(@Nonnull RepositoryHookContext context, @Nonnull Collection<RefChange> refChanges, @Nonnull HookResponse hookResponse) {
        Repository repository = context.getRepository();
        FileNameHookSetting setting = getSettings(context.getSettings());
        Optional<Pattern> affectedBranches = setting.getAffectedBranches();

        Collection<RefChange> filteredRefChanges = FluentIterable.from(refChanges)
                .filter(isNotDeleteRefChange)
                .filter(isNotTagRefChange)
                .toList();

        if(affectedBranches.isPresent()) {
            filteredRefChanges = Collections2.filter(filteredRefChanges, filterBranchesPredicate(affectedBranches.get()));
        }

        Iterable<Change> changes = Iterables.concat(changesetService.getChanges(filteredRefChanges, repository));

        Collection<String> filteredPaths = FluentIterable.from(changes)
                .filter(isNotDeleteChange)
                .transform(Functions.CHANGE_TO_PATH)
                .filter(Predicates.contains(setting.getIncludePattern()))
                .toList();

        if(setting.getExcludePattern().isPresent()) {
            Pattern excludePattern = setting.getExcludePattern().get();
            filteredPaths = Collections2.filter(filteredPaths, Predicates.not(Predicates.contains(excludePattern)));
        }

        if (filteredPaths.size() > 0) {
            hookResponse.out().println("=================================");
            for (String path : filteredPaths) {
                String msg = String.format("File [%s] violates file name pattern [%s].",
                        path, setting.getIncludePattern().pattern());
                if(affectedBranches.isPresent()) {
                    msg = String.format(msg + " For branches matching [%s] pattern.", affectedBranches.get());
                }
                hookResponse.out().println(msg);
            }
            hookResponse.out().println("=================================");
            return false;
        }
        return true;
    }

    private FileNameHookSetting getSettings(Settings settings) {
        String includeRegex = settings.getString(SETTINGS_INCLUDE_PATTERN);
        String excludeRegex = settings.getString(SETTINGS_EXCLUDE_PATTERN);
        String affectedBranches = settings.getString(SETTINGS_AFFECTED_BRANCHES);

        return new FileNameHookSetting(includeRegex, excludeRegex, affectedBranches);
    }

    @Override
    public void validate(Settings settings, SettingsValidationErrors errors, Repository repository) {

        if (Strings.isNullOrEmpty(settings.getString(SETTINGS_INCLUDE_PATTERN))){
            errors.addFieldError(SETTINGS_INCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
        } else {
            try {
                Pattern.compile(settings.getString(SETTINGS_INCLUDE_PATTERN, ""));
            } catch (PatternSyntaxException e) {
                errors.addFieldError(SETTINGS_INCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
            }
        }

        if (!Strings.isNullOrEmpty(settings.getString(SETTINGS_EXCLUDE_PATTERN))){
            try {
                Pattern.compile(settings.getString(SETTINGS_EXCLUDE_PATTERN));
            } catch (PatternSyntaxException e) {
                errors.addFieldError(SETTINGS_EXCLUDE_PATTERN, i18n.getText("filename-hook.error.pattern", "Pattern is not a valid regular expression"));
            }
        }

        if (!Strings.isNullOrEmpty(settings.getString(SETTINGS_AFFECTED_BRANCHES))) {
            try {
                Pattern.compile(settings.getString(SETTINGS_AFFECTED_BRANCHES));
            } catch (PatternSyntaxException e) {
                errors.addFieldError(SETTINGS_AFFECTED_BRANCHES, i18n.getText("filename-hook.error.pattern", "Affected branches pattern is not a valid regular expression"));
            }
        }
    }
}
