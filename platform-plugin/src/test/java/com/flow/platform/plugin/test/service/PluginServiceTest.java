/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flow.platform.plugin.test.service;

import com.flow.platform.plugin.domain.Plugin;
import com.flow.platform.plugin.domain.PluginStatus;
import com.flow.platform.plugin.event.PluginStatusChangeEvent;
import com.flow.platform.plugin.test.TestBase;
import com.flow.platform.util.git.JGitUtil;
import com.google.common.collect.ImmutableMultiset;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * @author yh@firim
 */
public class PluginServiceTest extends TestBase {

    protected ApplicationEventMulticaster applicationEventMulticaster;


    @Before
    public void init() throws Throwable {
        stubDemo();

        initGit();

        if (Files.exists(Paths.get(gitWorkspace.toString(), "plugin_cache.json"))) {
            Files.delete(Paths.get(gitWorkspace.toString(), "plugin_cache.json"));
        }

        applicationEventMulticaster = (ApplicationEventMulticaster) applicationContext
            .getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
    }

    @Test
    public void should_get_plugins_success() {

        // when: get plugins list
        List<Plugin> pluginList = pluginService.list();

        // then: pluginList not null
        Assert.assertNotNull(pluginList);

        // then: pluginList size is not 0
        Assert.assertNotEquals(0, pluginList.size());

        Assert.assertEquals(8, pluginList.size());

        // then: pluginList size is 2
        Assert.assertEquals(false, pluginList.isEmpty());
    }


    @Test
    public void should_update_success() {
        // when: update plugin status
        Plugin plugin = pluginService.find("flowCliC");
        plugin.setStatus(PluginStatus.INSTALLED);
        pluginStoreService.update(plugin);

        plugin = pluginService.find(plugin.getName());

        // then: plugin status should equal installed
        Assert.assertEquals(PluginStatus.INSTALLED, plugin.getStatus());

        resetPluginStatus();
    }

    @Test
    public void should_exec_install_success() throws Throwable {
        // delete folder avoid affect other
        cleanFolder("flowCliC");

        // when: find plugin
        Plugin plugin = pluginService.find("flowCliC");

        // then: plugin is not null
        Assert.assertNotNull(plugin);

        // when: install plugin
        pluginService.execInstallOrUpdate(plugin);

        plugin = pluginService.find("flowCliC");

        // then: plugin should install
        Assert.assertEquals(PluginStatus.INSTALLED, plugin.getStatus());

        // then: tag should not null
        Assert.assertNotNull(plugin.getTag());
    }

    @Test
    public void should_update_tag_success() throws Throwable {
        // delete folder avoid affect other
        cleanFolder("flowCli");

        // init local repo
        File mocGit = temporaryFolder.newFolder("test.git");
        File gitCloneMocGit = temporaryFolder.newFolder("test");
        JGitUtil.initBare(mocGit.toPath(), true);
        JGitUtil.clone(mocGit.toString(), gitCloneMocGit.toPath());

        // git commit something
        Files.createFile(Paths.get(gitCloneMocGit.toString(), "readme.md"));
        Git git = Git.open(gitCloneMocGit);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("test").call();
        JGitUtil.push(gitCloneMocGit.toPath(), "origin", "master");

        // git push tag
        git.tag().setName("1.0").setMessage("add tag 1.0").call();
        JGitUtil.push(gitCloneMocGit.toPath(), "origin", "1.0");

        // update plugin repo
        Plugin plugin = pluginService.find("flowCli");
        plugin.setDetails(mocGit.getParent() + "/test");
        pluginStoreService.update(plugin);

        // run install
        pluginService.execInstallOrUpdate(plugin);

        plugin = pluginService.find("flowCli");
        Assert.assertNotNull(plugin);
        Assert.assertEquals("1.0", plugin.getTag());
        Assert.assertEquals(PluginStatus.INSTALLED, plugin.getStatus());

        // add something again
        Files.createFile(Paths.get(gitCloneMocGit.toString(), "test.md"));

        git = Git.open(gitCloneMocGit);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("test").call();
        JGitUtil.push(gitCloneMocGit.toPath(), "origin", "master");

        // add tag again
        git.tag().setName("2.0").setMessage("add tag 2.0").call();
        JGitUtil.push(gitCloneMocGit.toPath(), "origin", "2.0");

        // install again
        pluginService.execInstallOrUpdate(plugin);

        plugin = pluginService.find("flowCli");
        Assert.assertNotNull(plugin);
        Assert.assertEquals("2.0", plugin.getTag());

        resetPluginStatus();
    }

    @Test
    public void should_stop_success_demo_first() throws Throwable {
        cleanFolder("flowCliE");

        CountDownLatch countDownLatch = new CountDownLatch(1);
        applicationEventMulticaster.addApplicationListener(new ApplicationListener<PluginStatusChangeEvent>() {

            @Override
            public void onApplicationEvent(PluginStatusChangeEvent event) {
                if (ImmutableMultiset.of(PluginStatus.INSTALLING).contains(event.getPluginStatus())) {
                    countDownLatch.countDown();
                }
            }
        });

        pluginService.install("flowCliE");
        countDownLatch.await(30, TimeUnit.SECONDS);
        pluginService.stop("flowCliE");

        Plugin plugin = pluginStoreService.find("flowCliE");
        Assert.assertEquals(PluginStatus.PENDING, plugin.getStatus());
        Assert.assertEquals(false, plugin.getStopped());
    }

    @Test
    public void should_stop_success_demo_second() throws Throwable {
        cleanFolder("flowCliB");

        Plugin plugin = pluginStoreService.find("flowCliB");
        plugin.setStopped(true);
        pluginService.install("flowCliB");

        Thread.sleep(1000);

        plugin = pluginStoreService.find("flowCliB");
        Assert.assertEquals(PluginStatus.PENDING, plugin.getStatus());
        Assert.assertEquals(false, plugin.getStopped());
    }

    private void resetPluginStatus() {
        Plugin plugin = pluginService.find("fircli");
        plugin.setStatus(PluginStatus.PENDING);
        pluginStoreService.update(plugin);
    }

    private void cleanFolder(String pluginName) throws IOException {
        Path gitFolder = Paths.get(gitWorkspace.toString(), pluginName);
        Path gitLocalFolder = Paths.get(gitCacheWorkspace.toString(), pluginName + ".git");

        if (gitFolder.toFile().exists()) {
            FileUtils.deleteDirectory(gitFolder.toFile());
        }

        if (gitLocalFolder.toFile().exists()) {
            FileUtils.deleteDirectory(gitLocalFolder.toFile());
        }
    }
}
