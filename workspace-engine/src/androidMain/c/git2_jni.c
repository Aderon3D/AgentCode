#include <jni.h>
#include <git2.h>
#include <string.h>
#include <sys/stat.h>

/* ponytail: thin JNI shim for libgit2 v1.8.1.
 * Each function opens/closes git_repository* per call (stateless).
 * Errors come back as a non-null jstring (NULL = success). */

/* Recursively create parent directories (like mkdir -p) */
static void mkdirs(const char *path) {
    char tmp[1024];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeInit(JNIEnv *env, jobject thiz) {
    int err = git_libgit2_init();
    return err < 0 ? (*env)->NewStringUTF(env, "git_libgit2_init failed") : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeInitRepo(
    JNIEnv *env, jobject thiz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    git_repository *r = NULL;
    git_config *cfg = NULL;
    jstring result = NULL;
    int err = git_repository_init(&r, path, 0);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Set default user.name + user.email so commits don't fail */
    err = git_repository_config(&cfg, r);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }
    err = git_config_set_string(cfg, "user.name", "AgentCode");
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }
    err = git_config_set_string(cfg, "user.email", "agent@aderon.dev");
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Override default HEAD to refs/heads/main (libgit2 may default to master) */
    {
        char head_path[1024];
        snprintf(head_path, sizeof(head_path), "%s/.git/HEAD", path);
        FILE *f = fopen(head_path, "w");
        if (f) { fprintf(f, "ref: refs/heads/main\n"); fclose(f); }
    }

    /* Add .worktrees/ to .gitignore — libgit2 merge treats the worktree
     * .git file as an invalid untracked path; CLI git ignores it. */
    {
        char gitignore_path[1024];
        snprintf(gitignore_path, sizeof(gitignore_path), "%s/.gitignore", path);
        FILE *f = fopen(gitignore_path, "a");
        if (f) { fprintf(f, "\n.worktrees/\n"); fclose(f); }
    }

out:
    if (cfg) git_config_free(cfg);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeShutdown(JNIEnv *env, jobject thiz) {
    git_libgit2_shutdown();
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeWorktreeAdd(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname, jstring jpath, jstring jbase) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *base = (*env)->GetStringUTFChars(env, jbase, NULL);

    git_repository *r = NULL;
    git_reference *head_ref = NULL;
    git_commit *head_commit = NULL;
    git_reference *branch_ref = NULL;
    git_worktree *wt = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Resolve the explicitly requested base ref/commit (not HEAD) */
    git_object *base_obj = NULL;
    err = git_revparse_single(&base_obj, r, base);
    if (err < 0) { result = (*env)->NewStringUTF(env, "could not resolve base ref"); goto out; }
    git_commit *base_commit = NULL;
    if (git_object_type(base_obj) != GIT_OBJECT_COMMIT) {
        result = (*env)->NewStringUTF(env, "base is not a commit"); goto out;
    }
    err = git_commit_lookup(&base_commit, r, git_object_id(base_obj));
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Create branch from resolved base */
    err = git_branch_create(&branch_ref, r, name, base_commit, 0);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Derive flat worktree name from path basename to avoid nested dirs.
     * e.g. name="agent/task-42", path=".../.worktrees/task-42"
     *   → worktree metadata dir = .git/worktrees/task-42 */
    const char *wt_name = strrchr(path, '/');
    wt_name = wt_name ? wt_name + 1 : path;

    /* Create parent directory of worktree path (libgit2 won't do mkdir -p) */
    char parent[1024];
    snprintf(parent, sizeof(parent), "%s", path);
    char *last_slash = strrchr(parent, '/');
    if (last_slash) { *last_slash = '\0'; mkdirs(parent); }

    git_worktree_add_options opts = GIT_WORKTREE_ADD_OPTIONS_INIT;
    opts.ref = branch_ref;
    err = git_worktree_add(&wt, r, wt_name, path, &opts);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (wt) git_worktree_free(wt);
    if (branch_ref) git_reference_free(branch_ref);
    if (base_commit) git_commit_free(base_commit);
    if (base_obj) git_object_free(base_obj);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jbase, base);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeWorktreeRemove(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    git_repository *r = NULL;
    git_worktree *wt = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Derive flat worktree name from path (e.g. ".worktrees/task-42" → "task-42") */
    const char *wt_name = strrchr(name, '/');
    wt_name = wt_name ? wt_name + 1 : name;

    err = git_worktree_lookup(&wt, r, wt_name);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    git_worktree_prune_options popts = GIT_WORKTREE_PRUNE_OPTIONS_INIT;
    popts.flags = GIT_WORKTREE_PRUNE_VALID | GIT_WORKTREE_PRUNE_LOCKED;
    err = git_worktree_prune(wt, &popts);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (wt) git_worktree_free(wt);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeCheckout(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jbranch) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *branch = (*env)->GetStringUTFChars(env, jbranch, NULL);

    git_repository *r = NULL;
    git_reference *ref = NULL;
    git_object *target = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* Resolve branch name to full refname if needed */
    char refname[512];
    if (strncmp(branch, "refs/", 5) == 0) {
        snprintf(refname, sizeof(refname), "%s", branch);
    } else {
        snprintf(refname, sizeof(refname), "refs/heads/%s", branch);
    }

    err = git_reference_lookup(&ref, r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_reference_peel(&target, ref, GIT_OBJECT_COMMIT);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* v1.8: git_checkout_tree takes 3 args */
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    err = git_checkout_tree(r, target, &opts);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_repository_set_head(r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (target) git_object_free(target);
    if (ref) git_reference_free(ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jbranch, branch);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeMergeSquash(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jbranch) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *branch = (*env)->GetStringUTFChars(env, jbranch, NULL);

    git_repository *r = NULL;
    git_reference *branch_ref = NULL;
    git_annotated_commit *their = NULL;
    git_merge_options merge_opts = GIT_MERGE_OPTIONS_INIT;
    git_checkout_options checkout_opts = GIT_CHECKOUT_OPTIONS_INIT;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    char refname[512];
    if (strncmp(branch, "refs/", 5) == 0) {
        snprintf(refname, sizeof(refname), "%s", branch);
    } else {
        snprintf(refname, sizeof(refname), "refs/heads/%s", branch);
    }

    err = git_reference_lookup(&branch_ref, r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_annotated_commit_from_ref(&their, r, branch_ref);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    merge_opts.flags = GIT_MERGE_FIND_RENAMES;
    checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    err = git_merge(r, (const git_annotated_commit **)&their, 1, &merge_opts, &checkout_opts);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (their) git_annotated_commit_free(their);
    if (branch_ref) git_reference_free(branch_ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jbranch, branch);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeAddAll(
    JNIEnv *env, jobject thiz,
    jstring jrepo) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    git_repository *r = NULL;
    git_index *idx = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_repository_index(&idx, r);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_index_add_all(idx, NULL, 0, NULL, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_index_write(idx);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (idx) git_index_free(idx);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeCommit(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jmessage) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *message = (*env)->GetStringUTFChars(env, jmessage, NULL);

    git_repository *r = NULL;
    git_oid commit_oid;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* v1.8: git_commit_create_from_stage — commits the staged index directly */
    err = git_commit_create_from_stage(&commit_oid, r, message, NULL);
    if (err == GIT_EUNCHANGED) {
        /* No changes to commit — not an error for probe purposes */
        result = NULL;
    } else if (err < 0) {
        result = (*env)->NewStringUTF(env, git_error_last()->message);
    }

out:
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jmessage, message);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeBranchDelete(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jname) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);

    git_repository *r = NULL;
    git_reference *ref = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    char refname[256];
    snprintf(refname, sizeof(refname), "refs/heads/%s", name);

    err = git_reference_lookup(&ref, r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    err = git_reference_delete(ref);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (ref) git_reference_free(ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_agent_code_workspace_LibGit2Backend_nativeBranchRename(
    JNIEnv *env, jobject thiz,
    jstring jrepo, jstring jold, jstring jnew) {
    const char *repo = (*env)->GetStringUTFChars(env, jrepo, NULL);
    const char *old = (*env)->GetStringUTFChars(env, jold, NULL);
    const char *new = (*env)->GetStringUTFChars(env, jnew, NULL);

    git_repository *r = NULL;
    git_reference *ref = NULL;
    git_reference *out = NULL;
    jstring result = NULL;
    int err;

    err = git_repository_open_ext(&r, repo, 0, NULL);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    char refname[256];
    snprintf(refname, sizeof(refname), "refs/heads/%s", old);

    err = git_reference_lookup(&ref, r, refname);
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

    /* force=0: error (not silent clobber) if newName already exists */
    char newref[256];
    snprintf(newref, sizeof(newref), "refs/heads/%s", new);
    err = git_reference_rename(&out, ref, newref, 0, "rename branch");
    if (err < 0) { result = (*env)->NewStringUTF(env, git_error_last()->message); goto out; }

out:
    if (out) git_reference_free(out);
    if (ref) git_reference_free(ref);
    if (r) git_repository_free(r);
    (*env)->ReleaseStringUTFChars(env, jrepo, repo);
    (*env)->ReleaseStringUTFChars(env, jold, old);
    (*env)->ReleaseStringUTFChars(env, jnew, new);
    return result;
}
